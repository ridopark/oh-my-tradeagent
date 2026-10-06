package com.ohmytradeagent.exec.broker;

import com.ohmytradeagent.exec.journal.ComboIntent;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Gated-condor Phase 3: works ONE opening multi-leg net-credit order down a mid-walk ladder.
 *
 * <p>Rung 0 asks the submit-time net-credit mid rounded to the tick. A rung unfilled after {@link
 * #RUNG_WAIT} is cancelled and replaced one tick LESS credit. The walk ABANDONS (fill-model data,
 * not a failure) before sending a rung below {@code modelCredit − 2 ticks} or at/after the
 * deadline. Every rung is its own journaled combo (intent_key and client_order_id prefixed {@value
 * #CONDOR_PREFIX} for attribution on the shared journal / broker account), so the ladder is
 * journaled by construction.
 *
 * <p>Cancel handling follows the port's 3-state contract exactly: {@code ALREADY_FILLED} reconciles
 * the fill and stops; {@code FAILED} is NEVER treated as flat — the walk halts with the order
 * possibly live (the FillPoller owns the SUBMITTED row from there); only {@code CANCELLED},
 * confirmed by a fresh {@link OptionsBroker#getOrderStatus} read, permits the next rung. A partial
 * fill on a cancelled rung stops the walk: the remainder is never re-sent.
 *
 * <p>Re-run of the same attempt (an activity retry, #897/#901): every rung already journaled is
 * resolved first, in order — RECORDED without a broker order id (the placement threw ambiguously)
 * is re-sent with the SAME client_order_id (the broker's duplicate-cid handling resolves it to the
 * prior order, never a second one) and then worked; SUBMITTED is settled (cancelled if still open);
 * a clean CANCELLED advances; FILLED / cancelled-with-fill return FILLED / PARTIAL so the hold
 * starts. Past the deadline nothing is (re-)sent, old rungs are still settled, and anything that
 * cannot be resolved HALTS (pages). So no path double-sends and no placed rung is left untracked.
 *
 * <p>Paper-only (#897 blocker 2): the broker is resolved FROM {@code brokerTarget} through the
 * {@link BrokerClientRegistry} — never handed in independently — and a non-{@code -paper} target is
 * rejected before the registry or the journal is touched. Every intent_key embeds {@code
 * tenant-strategy} so two tenants' same-day attempts can never collide on the global PK.
 */
public final class MidWalkExecutor {

  public static final String CONDOR_PREFIX = "condor-";
  static final Duration RUNG_WAIT = Duration.ofSeconds(10);
  static final int ABANDON_TICKS_BELOW_MODEL = 2;
  // Alpaca's DELETE acknowledges before the order leaves pending_cancel (status maps to OPEN), so
  // the post-cancel re-read polls briefly for the terminal CANCELLED before giving up.
  static final Duration CANCEL_CONFIRM_POLL = Duration.ofMillis(250);
  static final int CANCEL_CONFIRM_ATTEMPTS = 8;

  /** Blocking wait; injectable so tests advance a fake clock instead of sleeping. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration d) throws InterruptedException;
  }

  /** One leg with the NBBO captured at submit. {@code side} is {@code BUY}/{@code SELL}. */
  public record Leg(
      String optionSymbol, String side, long ratioQty, BigDecimal nbboBid, BigDecimal nbboAsk) {
    BigDecimal mid() {
      return nbboBid.add(nbboAsk).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }
  }

  /**
   * {@code attemptId} identifies the entry attempt within one (tenant, strategy); rung k's
   * intent_key is {@code condor-<tenantId>-<strategyId>-<attemptId>-r<k>}.
   */
  public record WalkRequest(
      String tenantId,
      String strategyId,
      String signalId,
      String brokerTarget,
      String attemptId,
      long qty,
      List<Leg> legs,
      BigDecimal modelCredit,
      BigDecimal tick,
      Instant deadline) {}

  public enum Outcome {
    FILLED,
    PARTIAL,
    ABANDONED,
    HALTED
  }

  /** One ladder rung: the credit asked and how it ended. */
  public record Rung(
      int step, String intentKey, String clientOrderId, BigDecimal limitCredit, String result) {}

  /**
   * {@code avgFillCredit}/{@code slippageVsMid} are set only for FILLED/PARTIAL. {@code reason} is
   * set for ABANDONED/HALTED.
   */
  public record Result(
      Outcome outcome,
      BigDecimal netMid,
      long filledQty,
      BigDecimal avgFillCredit,
      BigDecimal slippageVsMid,
      List<Rung> ladder,
      String reason) {}

  private final OrderIntentJournal journal;
  private final BrokerClientRegistry registry;
  private final Clock clock;
  private final Sleeper sleeper;

  public MidWalkExecutor(
      OrderIntentJournal journal, BrokerClientRegistry registry, Clock clock, Sleeper sleeper) {
    this.journal = journal;
    this.registry = registry;
    this.clock = clock;
    this.sleeper = sleeper;
  }

  /** True iff {@code brokerTarget} names a paper account ({@code <provider>-paper}). */
  public static boolean isPaperTarget(String brokerTarget) {
    return brokerTarget != null && brokerTarget.endsWith("-paper");
  }

  public Result execute(WalkRequest req) throws InterruptedException {
    if (!isPaperTarget(req.brokerTarget())) {
      throw new IllegalArgumentException(
          "mid-walk condor entry is paper-only, got broker_target=" + req.brokerTarget());
    }
    BigDecimal netMid = netMid(req.legs());
    BigDecimal minModelCredit = req.tick().multiply(BigDecimal.valueOf(ABANDON_TICKS_BELOW_MODEL));
    if (netMid.signum() <= 0 || req.modelCredit().compareTo(minModelCredit) <= 0) {
      // Never journal a rung the ladder could not legally ask for: a non-positive mid has no
      // credit to sell, and a model credit within 2 ticks of zero puts the floor at/below zero.
      return end(
          Outcome.ABANDONED,
          netMid,
          List.of(),
          "invalid_pricing: net_mid=" + netMid + " model_credit=" + req.modelCredit());
    }
    OptionsBroker broker =
        registry.brokerFor(req.tenantId(), BrokerClientRegistry.providerOf(req.brokerTarget()));
    BigDecimal floor = req.modelCredit().subtract(minModelCredit);
    BigDecimal price = roundToTick(netMid, req.tick());
    List<Rung> ladder = new ArrayList<>();
    int step = 0;

    // Recovery (#897 / #901): a re-run of this attempt first walks the rungs it already journaled,
    // r0..rN until the first absent one, and resolves each BEFORE the floor/deadline checks — so a
    // retry past the deadline still settles (or cancels) what an earlier attempt left at the
    // broker, and a rung that filled hands back FILLED so the hold starts.
    for (Optional<JournaledOrder> prior = journal.findByIntentKey(rungIntentKey(req, step));
        prior.isPresent();
        prior = journal.findByIntentKey(rungIntentKey(req, step))) {
      JournaledOrder row = prior.get();
      String intentKey = row.intentKey();
      BigDecimal rowPrice = row.limitPrice();
      Settled s;
      switch (row.state()) {
        case FILLED ->
            s =
                row.filledQty() == null || row.avgFillPrice() == null
                    ? new Settled(
                        "FILLED", null, false, "filled rung without fill detail: " + intentKey)
                    : new Settled(
                        "FILLED",
                        new BrokerFillDetail(row.filledQty(), row.avgFillPrice(), row.filledAt()),
                        false,
                        null);
        case CANCELLED ->
            s =
                row.filledQty() != null && row.filledQty() > 0
                    ? new Settled(
                        "PARTIAL",
                        new BrokerFillDetail(row.filledQty(), row.avgFillPrice(), row.filledAt()),
                        true,
                        null)
                    : new Settled("CANCELLED", null, false, null);
        case SUBMITTED -> {
          if (row.brokerOrderId() == null) {
            s = new Settled("SUBMITTED", null, false, "submitted rung without broker id");
          } else {
            s = settle(broker, intentKey, row.brokerOrderId());
          }
        }
        case RECORDED -> {
          if (row.brokerOrderId() != null) {
            s = settle(broker, intentKey, row.brokerOrderId());
          } else if (!clock.instant().isBefore(req.deadline())) {
            // Past the deadline nothing may be (re-)sent, and an unconfirmed placement cannot be
            // proven absent from the broker without one — page.
            s =
                new Settled(
                    "RECORDED", null, false, "unconfirmed rung past deadline: " + intentKey);
          } else {
            // The prior placement never confirmed: re-send the SAME client_order_id at the SAME
            // journaled credit (idempotent at the broker), then work it like a fresh rung.
            String brokerOrderId =
                place(broker, req, intentKey, ClientOrderId.forIntent(intentKey), rowPrice);
            sleeper.sleep(RUNG_WAIT);
            s = settle(broker, intentKey, brokerOrderId);
          }
        }
        default -> s = new Settled(row.state().name(), null, false, "rung " + row.state());
      }
      ladder.add(new Rung(step, intentKey, row.clientOrderId(), rowPrice, s.result()));
      if (s.fill() != null) {
        return filled(s, intentKey, netMid, ladder);
      }
      if (s.haltReason() != null) {
        return end(Outcome.HALTED, netMid, ladder, s.haltReason());
      }
      price = rowPrice.subtract(req.tick());
      step++;
    }

    for (; ; step++) {
      if (price.compareTo(floor) < 0) {
        return end(Outcome.ABANDONED, netMid, ladder, "below_floor: next " + price + " < " + floor);
      }
      if (!clock.instant().isBefore(req.deadline())) {
        return end(Outcome.ABANDONED, netMid, ladder, "deadline: " + req.deadline());
      }
      String intentKey = rungIntentKey(req, step);
      String clientOrderId = ClientOrderId.forIntent(intentKey);
      if (!journal.recordComboIntent(combo(req, intentKey, price))) {
        // Absent a moment ago, present now: a concurrent attempt is walking this ladder.
        return end(Outcome.HALTED, netMid, ladder, "rung journaled concurrently: " + intentKey);
      }
      String brokerOrderId = place(broker, req, intentKey, clientOrderId, price);
      sleeper.sleep(RUNG_WAIT);

      Settled s = settle(broker, intentKey, brokerOrderId);
      ladder.add(new Rung(step, intentKey, clientOrderId, price, s.result()));
      if (s.fill() != null) {
        return filled(s, intentKey, netMid, ladder);
      }
      if (s.haltReason() != null) {
        return end(Outcome.HALTED, netMid, ladder, s.haltReason());
      }
      price = price.subtract(req.tick());
    }
  }

  static String rungIntentKey(WalkRequest req, int step) {
    return CONDOR_PREFIX
        + req.tenantId()
        + "-"
        + req.strategyId()
        + "-"
        + req.attemptId()
        + "-r"
        + step;
  }

  /**
   * Places one rung and marks it SUBMITTED. A thrown placement is recorded on the row ({@code
   * markPlaceFailed}, best-effort so it never masks the broker error) and rethrown; the row stays
   * RECORDED with no broker order id, which a re-run recovers by re-sending the same cid.
   */
  private String place(
      OptionsBroker broker,
      WalkRequest req,
      String intentKey,
      String clientOrderId,
      BigDecimal price) {
    String brokerOrderId;
    try {
      brokerOrderId =
          broker
              .placeMlegOrder(
                  new PlaceMlegOrderRequest(
                      req.tenantId(), clientOrderId, req.qty(), price, mlegLegs(req.legs())))
              .brokerOrderId();
    } catch (RuntimeException e) {
      try {
        journal.markPlaceFailed(intentKey, e.getMessage());
      } catch (RuntimeException persistFailure) {
        e.addSuppressed(persistFailure);
      }
      throw e;
    }
    journal.markSubmittedIfRecorded(intentKey, brokerOrderId);
    return brokerOrderId;
  }

  /** How one rung ended: a fill (full or partial), a halt, or a clean confirmed cancel. */
  private record Settled(
      String result, BrokerFillDetail fill, boolean partial, String haltReason) {}

  private Settled settle(OptionsBroker broker, String intentKey, String brokerOrderId)
      throws InterruptedException {
    BrokerOrderStatus status = broker.getOrderStatus(brokerOrderId);
    if (status == BrokerOrderStatus.FILLED) {
      return fill(intentKey, broker.getFillDetail(brokerOrderId), "poll");
    }
    if (status != BrokerOrderStatus.OPEN) {
      return new Settled(status.name(), null, false, "rung ended unfilled: " + status);
    }
    journal.markCancelAttempted(intentKey);
    CancelResponse cancel = broker.cancelOrder(brokerOrderId);
    switch (cancel.outcome()) {
      case ALREADY_FILLED -> {
        return fill(intentKey, broker.getFillDetail(brokerOrderId), "cancel_reconcile");
      }
      case FAILED -> {
        journal.markCancelFailed(intentKey, cancel.brokerReason());
        return new Settled(
            "CANCEL_FAILED",
            null,
            false,
            "cancel failed, order may be live: " + cancel.brokerReason());
      }
      case CANCELLED -> {
        // fall through to the confirming re-read below
      }
    }
    BrokerOrderStatus after = broker.getOrderStatus(brokerOrderId);
    for (int i = 1; after == BrokerOrderStatus.OPEN && i < CANCEL_CONFIRM_ATTEMPTS; i++) {
      sleeper.sleep(CANCEL_CONFIRM_POLL);
      after = broker.getOrderStatus(brokerOrderId);
    }
    if (after == BrokerOrderStatus.FILLED) {
      return fill(intentKey, broker.getFillDetail(brokerOrderId), "poll");
    }
    if (after != BrokerOrderStatus.CANCELLED) {
      return new Settled(after.name(), null, false, "cancel not confirmed, status " + after);
    }
    BrokerFillDetail partial = broker.getPartialFillSnapshot(brokerOrderId);
    if (partial.filledQty() > 0) {
      journal.markCancelledWithFill(
          intentKey, partial.filledQty(), partial.avgFillPrice(), partial.filledAt());
      return new Settled("PARTIAL", partial, true, null);
    }
    journal.markCancelledIfSubmitted(intentKey);
    return new Settled("CANCELLED", null, false, null);
  }

  private Settled fill(String intentKey, BrokerFillDetail detail, String detectedVia) {
    journal.markFilled(
        intentKey, detail.filledQty(), detail.avgFillPrice(), detail.filledAt(), detectedVia);
    return new Settled("FILLED", detail, false, null);
  }

  private Result filled(Settled s, String intentKey, BigDecimal netMid, List<Rung> ladder) {
    BigDecimal slippage = slippageVsMid(netMid, s.fill().avgFillPrice());
    journal.recordSlippageVsMid(intentKey, slippage);
    return new Result(
        s.partial() ? Outcome.PARTIAL : Outcome.FILLED,
        netMid,
        s.fill().filledQty(),
        s.fill().avgFillPrice().abs(),
        slippage,
        List.copyOf(ladder),
        null);
  }

  private static Result end(Outcome outcome, BigDecimal netMid, List<Rung> ladder, String reason) {
    return new Result(outcome, netMid, 0L, null, null, List.copyOf(ladder), reason);
  }

  /** Net credit at mid: short legs (+) minus long legs (−), each weighted by its ratio. */
  static BigDecimal netMid(List<Leg> legs) {
    BigDecimal net = BigDecimal.ZERO;
    for (Leg leg : legs) {
      BigDecimal legMid = leg.mid().multiply(BigDecimal.valueOf(leg.ratioQty()));
      net = "SELL".equalsIgnoreCase(leg.side()) ? net.add(legMid) : net.subtract(legMid);
    }
    return net;
  }

  /**
   * Credit given up vs the submit-time mid: {@code netMid − |avgFillPrice|}; positive = worse than
   * mid. The ABS because Alpaca reports an mleg credit fill in its negative-is-credit notation.
   */
  static BigDecimal slippageVsMid(BigDecimal netMid, BigDecimal avgFillPrice) {
    return netMid.subtract(avgFillPrice.abs());
  }

  private static BigDecimal roundToTick(BigDecimal value, BigDecimal tick) {
    return value.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick);
  }

  private static List<PlaceMlegOrderRequest.Leg> mlegLegs(List<Leg> legs) {
    return legs.stream()
        .map(l -> new PlaceMlegOrderRequest.Leg(l.optionSymbol(), l.side(), l.ratioQty()))
        .toList();
  }

  private ComboIntent combo(WalkRequest req, String intentKey, BigDecimal price) {
    return new ComboIntent(
        intentKey,
        req.signalId(),
        req.tenantId(),
        req.strategyId(),
        req.brokerTarget(),
        req.qty(),
        price,
        req.legs().stream()
            .map(
                l ->
                    new ComboIntent.Leg(
                        l.optionSymbol(),
                        l.side().toUpperCase(Locale.ROOT),
                        l.ratioQty(),
                        l.nbboBid(),
                        l.nbboAsk(),
                        l.mid()))
            .toList(),
        OffsetDateTime.now(clock));
  }
}
