package com.ohmytradeagent.exec.activities;

import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.activities.CondorExecActivity;
import com.ohmytradeagent.contract.activities.CondorMarketActivity.CondorLeg;
import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.BrokerFillDetail;
import com.ohmytradeagent.exec.broker.BrokerOrderStatus;
import com.ohmytradeagent.exec.broker.ClientOrderId;
import com.ohmytradeagent.exec.broker.MidWalkExecutor;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import com.ohmytradeagent.exec.journal.OrderState;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gated-condor Phase 4 exec impl of {@link CondorExecActivity}: the mid-walk entry, the
 * shorts-first flatten and the settlement holdings read, all PAPER-ONLY.
 *
 * <p>Paper guard (#897 blocker 2), checked before any broker or journal touch: the request's {@code
 * brokerTarget} must be {@code <provider>-paper} AND must be the target whose queue this worker
 * serves ({@code broker-<brokerTarget>}), so a request mis-routed to a live exec pod is refused
 * even when its label says paper. The broker handle is always resolved FROM that target through the
 * {@link BrokerClientRegistry}.
 */
@Component
public class CondorExecActivityImpl implements CondorExecActivity {

  static final String NOT_PAPER_ERROR = "CondorNotPaperError";
  static final String FLATTEN_DETECTED_VIA = "condor_flatten";
  // A short-cover market order on a 0DTE XSP leg fills in well under a second; 20 × 500ms bounds
  // the wait for the shorts before the longs are touched.
  static final Duration SHORT_FILL_POLL = Duration.ofMillis(500);
  static final int SHORT_FILL_ATTEMPTS = 20;
  static final java.util.Set<BrokerOrderStatus> TERMINAL_UNFILLED =
      java.util.EnumSet.of(
          BrokerOrderStatus.CANCELLED, BrokerOrderStatus.REJECTED, BrokerOrderStatus.EXPIRED);

  private final OrderIntentJournal journal;
  private final BrokerClientRegistry registry;
  private final String taskQueue;
  private final Clock clock;
  private final MidWalkExecutor.Sleeper sleeper;
  private final MidWalkExecutor walker;

  @Autowired
  public CondorExecActivityImpl(
      OrderIntentJournal journal,
      BrokerClientRegistry registry,
      @Value("${temporal.task-queue:broker-alpaca-paper}") String taskQueue) {
    this(
        journal,
        registry,
        taskQueue,
        Clock.systemUTC(),
        CondorExecActivityImpl::heartbeatThenSleep);
  }

  CondorExecActivityImpl(
      OrderIntentJournal journal,
      BrokerClientRegistry registry,
      String taskQueue,
      Clock clock,
      MidWalkExecutor.Sleeper sleeper) {
    this.journal = journal;
    this.registry = registry;
    this.taskQueue = taskQueue;
    this.clock = clock;
    this.sleeper = sleeper;
    this.walker = new MidWalkExecutor(journal, registry, clock, sleeper);
  }

  /**
   * Every wait in the walk and the flatten heartbeats first, so the workflow's heartbeat timeout
   * (#901) detects a dead worker within one rung instead of only at start-to-close.
   */
  private static void heartbeatThenSleep(Duration d) throws InterruptedException {
    Activity.getExecutionContext().heartbeat(null);
    Thread.sleep(d.toMillis());
  }

  @Override
  public CondorEntryResult enterCondor(CondorEntryRequest req) {
    requirePaperQueue(req.brokerTarget());
    MidWalkExecutor.Result r;
    try {
      r =
          walker.execute(
              new MidWalkExecutor.WalkRequest(
                  req.tenantId(),
                  req.strategyId(),
                  req.attemptId(),
                  req.brokerTarget(),
                  req.attemptId(),
                  req.qty(),
                  req.legs().stream()
                      .map(
                          l ->
                              new MidWalkExecutor.Leg(
                                  l.occSymbol(),
                                  l.side().toUpperCase(Locale.ROOT),
                                  1L,
                                  l.bid(),
                                  l.ask()))
                      .toList(),
                  req.modelCredit(),
                  req.tick(),
                  Instant.ofEpochMilli(req.deadlineEpochMs())));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ApplicationFailure.newFailure("condor entry interrupted", "CondorEntryInterrupted");
    }
    return new CondorEntryResult(
        r.outcome().name(),
        r.netMid(),
        r.filledQty(),
        r.avgFillCredit(),
        r.slippageVsMid(),
        r.ladder().size(),
        r.reason());
  }

  @Override
  public CondorFlattenResult flattenCondor(CondorFlattenRequest req) {
    requirePaperQueue(req.brokerTarget());
    OptionsBroker broker =
        registry.brokerFor(req.tenantId(), BrokerClientRegistry.providerOf(req.brokerTarget()));
    Map<String, String> shortOrders = new LinkedHashMap<>(); // intent_key -> broker order id
    List<Integer> longIdx = new ArrayList<>();
    for (int i = 0; i < req.legs().size(); i++) {
      if ("sell".equalsIgnoreCase(req.legs().get(i).side())) {
        String intentKey = closeIntentKey(req, i);
        shortOrders.put(intentKey, close(broker, req, i, "BUY"));
      } else {
        longIdx.add(i);
      }
    }
    try {
      if (!shortsFilled(broker, shortOrders)) {
        return new CondorFlattenResult(
            false, false, "short cover not confirmed filled; long wings left in place");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ApplicationFailure.newFailure("condor flatten interrupted", "CondorFlattenInterrupted");
    }
    for (int i : longIdx) {
      close(broker, req, i, "SELL");
    }
    return new CondorFlattenResult(true, true, null);
  }

  @Override
  public List<HeldLeg> heldCondorLegs(
      String tenantId, String brokerTarget, List<String> occSymbols) {
    requirePaperQueue(brokerTarget);
    Map<String, Long> book =
        registry
            .brokerFor(tenantId, BrokerClientRegistry.providerOf(brokerTarget))
            .signedOptionPositions();
    List<HeldLeg> held = new ArrayList<>();
    for (String occ : occSymbols) {
      Long qty = book.get(occ.replace(" ", ""));
      if (qty != null && qty != 0L) {
        held.add(new HeldLeg(occ, qty));
      }
    }
    return held;
  }

  private void requirePaperQueue(String brokerTarget) {
    if (!MidWalkExecutor.isPaperTarget(brokerTarget)
        || !taskQueue.equals("broker-" + brokerTarget)) {
      throw ApplicationFailure.newNonRetryableFailure(
          "condor orders are paper-only: broker_target="
              + brokerTarget
              + " on worker queue "
              + taskQueue,
          NOT_PAPER_ERROR);
    }
  }

  static String closeIntentKey(CondorFlattenRequest req, int legIndex) {
    return MidWalkExecutor.CONDOR_PREFIX
        + req.tenantId()
        + "-"
        + req.strategyId()
        + "-"
        + req.attemptId()
        + "-x"
        + legIndex;
  }

  /**
   * Journals (idempotently) and places one closing market order for leg {@code i}; returns its
   * broker order id, or null when the broker confirmed the leg already flat. A re-run reuses the
   * SUBMITTED/FILLED row rather than re-sending.
   */
  private String close(OptionsBroker broker, CondorFlattenRequest req, int i, String side) {
    CondorLeg leg = req.legs().get(i);
    String intentKey = closeIntentKey(req, i);
    journal.upsertIntent(
        new OrderIntent()
            .withSchemaVersion(1L)
            .withTenantId(req.tenantId())
            .withStrategyId(req.strategyId())
            .withIntentKey(intentKey)
            .withSignalId(req.attemptId())
            .withBrokerTarget(OrderIntent.BrokerTarget.fromValue(req.brokerTarget()))
            .withOptionSymbol(leg.occSymbol())
            .withSide(OrderIntent.Side.fromValue(side))
            .withQty(req.qty())
            .withRecordedAt(OffsetDateTime.now(clock)));
    JournaledOrder row = journal.findByIntentKey(intentKey).orElseThrow();
    if ((row.state() == OrderState.SUBMITTED || row.state() == OrderState.FILLED)
        && row.brokerOrderId() != null) {
      return row.brokerOrderId();
    }
    PlaceOrderResponse resp;
    try {
      resp =
          broker.placeClosingOrder(
              new PlaceOrderRequest(
                  req.tenantId(),
                  ClientOrderId.forIntent(intentKey),
                  leg.occSymbol(),
                  side,
                  req.qty(),
                  null));
    } catch (RuntimeException e) {
      try {
        journal.markPlaceFailed(intentKey, e.getMessage());
      } catch (RuntimeException persistFailure) {
        e.addSuppressed(persistFailure);
      }
      throw e;
    }
    if (resp.alreadyClosed()) {
      journal.markClosedAlreadyFlat(intentKey, "condor flatten: broker-confirmed flat");
      return null;
    }
    journal.markSubmittedIfRecorded(intentKey, resp.brokerOrderId());
    return resp.brokerOrderId();
  }

  /** Polls each short cover until FILLED (journaling the fill); false if any is not filled. */
  private boolean shortsFilled(OptionsBroker broker, Map<String, String> shortOrders)
      throws InterruptedException {
    for (Map.Entry<String, String> e : shortOrders.entrySet()) {
      String brokerOrderId = e.getValue();
      if (brokerOrderId == null) {
        continue; // broker-confirmed already flat
      }
      BrokerOrderStatus status = broker.getOrderStatus(brokerOrderId);
      for (int i = 1; status != BrokerOrderStatus.FILLED && i < SHORT_FILL_ATTEMPTS; i++) {
        if (TERMINAL_UNFILLED.contains(status)) {
          return false; // cancelled/rejected/expired: waiting cannot fill it
        }
        sleeper.sleep(SHORT_FILL_POLL);
        status = broker.getOrderStatus(brokerOrderId);
      }
      if (status != BrokerOrderStatus.FILLED) {
        return false;
      }
      BrokerFillDetail fill = broker.getFillDetail(brokerOrderId);
      journal.markFilled(
          e.getKey(), fill.filledQty(), fill.avgFillPrice(), fill.filledAt(), FLATTEN_DETECTED_VIA);
    }
    return true;
  }
}
