package com.ohmytradeagent.exec.activities;

import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.exec.alert.BrokerRejectionAlerter;
import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.BrokerFillDetail;
import com.ohmytradeagent.exec.broker.CancelResponse;
import com.ohmytradeagent.exec.broker.ClientOrderId;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import com.ohmytradeagent.exec.broker.alpaca.AlpacaPaperBroker;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import com.ohmytradeagent.exec.journal.OrderState;
import io.temporal.failure.ApplicationFailure;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * The Phase 2b implementation of the plan's 3-layer idempotency model (line 362). Each method
 * short-circuits on the journal's current state so repeated Activity attempts (Temporal retries,
 * orchestrator-svc restarts, concurrent task-queue workers) converge on a single broker order per
 * {@code intent_key}.
 *
 * <p>{@link #placeOrder} flow:
 *
 * <pre>
 *   1. journal.upsertIntent(intent)                  // INSERT ON CONFLICT DO NOTHING
 *   2. journal.findByIntentKey                       // read canonical row
 *   3. if state == SUBMITTED       → return existing (skip broker call)
 *   4. broker.placeOrder(client_order_id = ClientOrderId.forIntent(intent_key))  // bounded (#295)
 *   5. journal.markSubmittedIfRecorded               // WHERE state = 'RECORDED'
 *   6. return SUBMITTED result
 * </pre>
 */
@Component
public class ExecActivitiesImpl implements ExecActivities {

  private static final Logger log = LoggerFactory.getLogger(ExecActivitiesImpl.class);

  private final OrderIntentJournal journal;
  private final BrokerClientRegistry brokerRegistry;
  private final BrokerRejectionAlerter rejectionAlerter;

  public ExecActivitiesImpl(
      OrderIntentJournal journal,
      BrokerClientRegistry brokerRegistry,
      BrokerRejectionAlerter rejectionAlerter) {
    this.journal = journal;
    this.brokerRegistry = brokerRegistry;
    this.rejectionAlerter = rejectionAlerter;
  }

  @Override
  public OrderIntentResult placeOrder(OrderIntent intent) {
    validateIntent(intent);
    journal.upsertIntent(intent);
    JournaledOrder row =
        journal
            .findByIntentKey(intent.getIntentKey())
            .orElseThrow(
                () ->
                    ApplicationFailure.newNonRetryableFailure(
                        "Journal row vanished after upsert: " + intent.getIntentKey(),
                        "JournalConsistencyError"));

    if (row.state() == OrderState.SUBMITTED) {
      return result(row);
    }

    // Issue #295: the broker-facing client_order_id is the bounded, hashed value derived from the
    // (possibly 161-char) intent_key — Alpaca caps client_order_id at 128. JooqOrderIntentJournal
    // persists this SAME value in the client_order_id column, so the wire id, the stored column,
    // and
    // the WS-echoed id all match. The intent_key stays unchanged (journal PK / :exit: STC routing).
    String clientOrderId = ClientOrderId.forIntent(intent.getIntentKey());
    // P4-a: resolve the broker for THIS intent's (tenant, provider) once via the registry instead
    // of
    // a single injected OptionsBroker. Under the env-fallback credential source every key resolves
    // to
    // the same env cred set, so the resolved client is byte-identical to the pre-P4-a single
    // broker;
    // P4-b makes it per-tenant. brokerTarget is guaranteed non-null by validateIntent above.
    OptionsBroker broker =
        brokerRegistry.brokerFor(
            intent.getTenantId(),
            BrokerClientRegistry.providerOf(intent.getBrokerTarget().value()),
            intent.getBrokerAccountId());
    // P1 multi-tenant-credentials: carry tenant_id to the broker boundary, and surface it on the
    // MDC
    // for the duration of the broker call so later phases can resolve per-tenant credentials and
    // assert the account. The value is threaded only — the Alpaca request body is unchanged this
    // phase. MDC is scoped to the placement via try-with-resources (auto-removed on close) so it
    // cannot leak onto the pooled Temporal worker thread.
    PlaceOrderResponse br;
    try (MDC.MDCCloseable ignored = MDC.putCloseable("tenant_id", intent.getTenantId())) {
      br =
          broker.placeOrder(
              new PlaceOrderRequest(
                  intent.getTenantId(),
                  clientOrderId,
                  intent.getOptionSymbol(),
                  intent.getSide().value(),
                  reduceOnlyQty(broker, intent, clientOrderId),
                  intent.getLimitPrice()));
    } catch (RuntimeException e) {
      // Issue #295: surface the broker rejection at the DB layer. Without this the row stays
      // RECORDED with last_error=NULL and a broker-side outage (e.g. the 128-char 422) is
      // invisible.
      // State is left RECORDED so a later retry can still place. The persist is best-effort: a
      // failure here (e.g. DB down) must NOT mask the original broker exception, whose
      // retryable/non-retryable classification Temporal relies on (a swallowed InvalidRequestError
      // replaced by a generic DB RuntimeException would retry forever — the #264 retry-storm
      // class).
      // Phase 2 (prod_real intentional halt): a 403 40310000 "account orders blocked" rejection is
      // terminal and non-retryable. For THAT class only, terminalize the intent RECORDED -> ERRORED
      // (markErrored) so it does not park in RECORDED with the failure burning Temporal retries;
      // the
      // non-retryable classification already caps Temporal at a single attempt. All OTHER errors
      // keep the existing markPlaceFailed/RECORDED behaviour so a transient failure can still
      // retry.
      boolean accountOrdersBlocked =
          e instanceof ApplicationFailure
              && AlpacaPaperBroker.ACCOUNT_ORDERS_BLOCKED_ERROR_TYPE.equals(
                  ((ApplicationFailure) e).getType());
      try {
        if (accountOrdersBlocked) {
          journal.markErrored(intent.getIntentKey(), e.getMessage());
        } else {
          journal.markPlaceFailed(intent.getIntentKey(), e.getMessage());
        }
      } catch (RuntimeException persistFailure) {
        e.addSuppressed(persistFailure);
      }
      // Issue #297: best-effort Discord alert on the broker rejection that caused the #295 outage.
      // The alerter never throws and is invoked AFTER the journal write and BEFORE the original
      // broker exception is rethrown unchanged — it must not alter the exception's
      // retryable/non-retryable classification that Temporal relies on (the #264 retry-storm
      // class).
      rejectionAlerter.onBrokerRejection(intent, clientOrderId, e.getMessage());
      throw e;
    }

    if (br.alreadyClosed()) {
      // PLAN-over-exit-422: the SELL/STC was a broker-confirmed over-exit — Alpaca rejected it with
      // a "position intent mismatch" 422 AND /v2/positions confirmed the OCC was already flat. This
      // is BENIGN (nothing to sell), NOT a failure: terminalize the journal RECORDED → CANCELLED so
      // it does not orphan, and DO NOT call rejectionAlerter (that is the exception-path pager only
      // —
      // we are on the success branch). The returned OrderIntentResult carries state=CANCELLED /
      // brokerOrderId=null (via result(row)); the orchestrator zeroes remainingQty and emits the
      // visible, non-paging PartialExitAlreadyFlat audit.
      journal.markClosedAlreadyFlat(
          intent.getIntentKey(), "benign over-exit: broker-confirmed flat");
      return journal
          .findByIntentKey(intent.getIntentKey())
          .map(ExecActivitiesImpl::result)
          .orElseThrow(
              () ->
                  ApplicationFailure.newNonRetryableFailure(
                      "Journal row missing post-mark: " + intent.getIntentKey(),
                      "JournalConsistencyError"));
    }

    journal.markSubmittedIfRecorded(intent.getIntentKey(), br.brokerOrderId());

    return journal
        .findByIntentKey(intent.getIntentKey())
        .map(ExecActivitiesImpl::result)
        .orElseThrow(
            () ->
                ApplicationFailure.newNonRetryableFailure(
                    "Journal row missing post-mark: " + intent.getIntentKey(),
                    "JournalConsistencyError"));
  }

  @Override
  public OrderIntentResult cancelOrder(String intentKey) {
    JournaledOrder row =
        journal
            .findByIntentKey(intentKey)
            .orElseThrow(
                () ->
                    ApplicationFailure.newNonRetryableFailure(
                        "Cannot cancel: no journal row for " + intentKey,
                        "JournalConsistencyError"));

    if (row.state() == OrderState.CANCELLED) {
      return result(row);
    }
    if (row.brokerOrderId() == null) {
      // Pre-broker cancel: just flip state, nothing to call.
      journal.markCancelled(intentKey);
      return journal.findByIntentKey(intentKey).map(ExecActivitiesImpl::result).orElseThrow();
    }

    // P4-a: resolve the broker ONCE from the journaled row's (tenantId, brokerTarget) and reuse the
    // same handle for both the cancel and the cancel-on-filled getFillDetail follow-up.
    OptionsBroker broker =
        brokerRegistry.brokerFor(
            row.tenantId(), BrokerClientRegistry.providerOf(row.brokerTarget()));
    journal.markCancelAttempted(intentKey);
    CancelResponse cancel = broker.cancelOrder(row.brokerOrderId());
    switch (cancel.outcome()) {
      case CANCELLED -> journal.markCancelled(intentKey);
      case FAILED -> journal.markCancelFailed(intentKey, cancel.brokerReason());
      case ALREADY_FILLED -> {
        // Issue #165: the broker filled the order while the cancel was in flight. Pull the
        // broker-confirmed fill detail and reconcile the journal to FILLED so the orchestrator
        // can spawn the missing PositionWorkflow instead of orphaning the position. A repeat
        // call lands as a no-op (markFilled is conditional on RECORDED/SUBMITTED state).
        BrokerFillDetail fill = broker.getFillDetail(row.brokerOrderId());
        journal.markFilled(intentKey, fill.filledQty(), fill.avgFillPrice(), fill.filledAt());
      }
    }
    return journal.findByIntentKey(intentKey).map(ExecActivitiesImpl::result).orElseThrow();
  }

  @Override
  public OrderIntentResult getOrderStatus(String intentKey) {
    JournaledOrder row =
        journal
            .findByIntentKey(intentKey)
            .orElseThrow(
                () ->
                    ApplicationFailure.newNonRetryableFailure(
                        "No journal row for " + intentKey, "JournalConsistencyError"));
    return result(row);
  }

  /**
   * Issue #264: fail fast on a malformed {@link OrderIntent} before the journal upsert / broker
   * call. A null required field (the {@code brokerTarget} enum is dereferenced by {@code
   * JooqOrderIntentJournal.upsertIntent}, and {@code side}/{@code optionSymbol}/{@code qty} by the
   * broker request below) would otherwise NPE — and because a bare {@link NullPointerException} is
   * retryable under Temporal's default activity policy, the activity loops unbounded (the 1637+
   * retry storm the issue reports). Throwing a non-retryable {@link ApplicationFailure} (mirroring
   * the {@code JournalConsistencyError} / {@code InvalidBrokerTargetError} precedents) terminates a
   * malformed intent immediately and names the offending field for the operator.
   */
  /**
   * Reduce-only clamp (#716): never ask the broker to sell more of a contract than it says we hold.
   *
   * <p>On 2026-08-17 the orchestrator requested SELL 35 while the broker held 24 (and 18 holding
   * 12), because the flatten was sized inside the ~30s window in which an already-filled partial
   * exit had not yet been booked. Alpaca refused both with {@code account not eligible to trade
   * uncovered option contracts} — an account PERMISSION we neither control nor designed as a safety
   * net, and the only thing standing between that bug and short calls on a real account. This is
   * the control we do own, and it sits at the one choke point every SELL passes through, so it
   * covers STC partials, flattens and operator trims alike — unlike the workflow-side trim clamp
   * from #659, which is reachable only on an operator trim.
   *
   * <p><b>Fail-open by construction.</b> The clamp fires ONLY when the contract is actually present
   * in the broker's open positions AND the held quantity is smaller than the request. Everything
   * else places as asked. That is deliberate, not laziness:
   *
   * <ul>
   *   <li>{@link OptionsBroker#listOpenPositions()} DEFAULTS TO AN EMPTY LIST, so reading "absent"
   *       as "flat" would clamp every sell to zero on any broker that does not implement it.
   *   <li>A positions-API outage must not block a risk-reducing exit. Being unable to sell is a
   *       worse failure than the tail this prevents, and the broker's own block still backstops the
   *       naked case.
   * </ul>
   *
   * <p>The consequence of a clamp is deliberately accepted: the journal keeps the REQUESTED qty
   * while the fill reports the clamped one, which reads downstream as a partial fill, and the
   * workflow will believe a remainder exists until reconciliation corrects it. That is a phantom to
   * clean up rather than a short position to buy back.
   *
   * <p>OCCs are compared with spaces stripped: the padded form circulates in the journal and
   * workflow ids while Alpaca returns the compact one, and a raw comparison would silently never
   * match — leaving a guard that looks present and never fires.
   */
  private long reduceOnlyQty(OptionsBroker broker, OrderIntent intent, String clientOrderId) {
    long requested = intent.getQty();
    if (intent.getSide() != OrderIntent.Side.SELL) {
      return requested;
    }
    List<BrokerPosition> open;
    try {
      open = broker.listOpenPositions();
    } catch (RuntimeException e) {
      log.warn(
          "reduce-only clamp skipped: positions unavailable for {} intent={} — placing as"
              + " requested qty={}",
          intent.getOptionSymbol(),
          intent.getIntentKey(),
          requested,
          e);
      return requested;
    }
    if (open == null || open.isEmpty()) {
      return requested;
    }
    String wanted = compactOcc(intent.getOptionSymbol());
    Long held = null;
    for (BrokerPosition p : open) {
      if (p != null && wanted != null && wanted.equals(compactOcc(p.getOptionSymbol()))) {
        held = p.getQty();
        break;
      }
    }
    if (held == null || held >= requested) {
      return requested;
    }
    // A clamp means something upstream asked to sell contracts that do not exist. That is a bug
    // signal, not routine hygiene — page it. Silence is precisely how this class of defect
    // survived unnoticed.
    log.error(
        "REDUCE-ONLY CLAMP {} intent={} requested={} broker_held={} — placing {}",
        intent.getOptionSymbol(),
        intent.getIntentKey(),
        requested,
        held,
        held);
    rejectionAlerter.onBrokerRejection(
        intent,
        clientOrderId,
        "reduce-only clamp: requested SELL "
            + requested
            + " but broker holds "
            + held
            + " of "
            + intent.getOptionSymbol()
            + " — placed "
            + held);
    return held;
  }

  /** Padded and compact OCC forms both circulate; compare on the compact one. */
  private static String compactOcc(String occ) {
    return occ == null ? null : occ.replace(" ", "");
  }

  private static void validateIntent(OrderIntent intent) {
    String intentKey = intent.getIntentKey();
    requirePresent(intentKey, "intentKey", intentKey);
    requirePresent(intent.getBrokerTarget(), "brokerTarget", intentKey);
    requirePresent(intent.getOptionSymbol(), "optionSymbol", intentKey);
    requirePresent(intent.getSide(), "side", intentKey);
    requirePresent(intent.getQty(), "qty", intentKey);
  }

  private static void requirePresent(Object value, String field, String intentKey) {
    if (value == null) {
      throw ApplicationFailure.newNonRetryableFailure(
          "OrderIntent." + field + " is required but was null (intentKey=" + intentKey + ")",
          "InvalidOrderIntentError");
    }
  }

  private static OrderIntentResult result(JournaledOrder row) {
    OrderIntentResult r = new OrderIntentResult();
    r.setSchemaVersion(1L);
    r.setIntentKey(row.intentKey());
    r.setBrokerOrderId(row.brokerOrderId());
    r.setState(OrderIntentResult.State.fromValue(row.state().name()));
    r.setLastStateAt(row.lastStateAt() != null ? row.lastStateAt() : OffsetDateTime.now());
    r.setLastError(row.lastError());
    // Issue #165 phase 2: surface broker-confirmed fill detail so the orchestrator can synthesise
    // a FillEvent on the cancel-on-filled recovery path. Nullable for non-FILLED rows.
    r.setFilledQty(row.filledQty());
    r.setAvgFillPrice(row.avgFillPrice());
    return r;
  }
}
