package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AdoptionWorkflowInput;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.BrokerOpenOrder;
import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.JournalEntry;
import com.ohmytradeagent.contract.ReconciliationSummary;
import com.ohmytradeagent.contract.ReconciliationWorkflowInput;
import com.ohmytradeagent.contract.activities.ReconciliationExecActivity;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.AuditQueryActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.activities.ReconciliationMetricsActivities;
import com.ohmytradeagent.orchestrator.domain.OccSymbol;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 5 reconciliation impl. Routes the broker-side Activity to the correct task queue based on
 * {@code input.getBrokerTarget()} so paper / live deploys run against isolated journals + broker
 * connections.
 */
public class ReconciliationWorkflowImpl implements ReconciliationWorkflow {

  /** Journal entries older than this with no broker match are treated as orphans. */
  static final Duration JOURNAL_ORPHAN_STALE = Duration.ofMinutes(5);

  /**
   * Issue #206: debounce window for repeat orphan detection. If the same orphan key was already
   * audited within this window, suppress the per-cycle duplicate. Replay-safe because
   * reconciliation workflows are cron-fresh (per-tick) — state is derived from {@code audit_log}
   * via {@link AuditQueryActivities}, not from in-workflow variables.
   */
  static final Duration ORPHAN_DEBOUNCE_WINDOW = Duration.ofHours(1);

  /**
   * Issue #219: emit a {@code *OrphanOngoing} escalation audit when the earliest matching {@code
   * *Orphan} row in {@code audit_log} is older than this window — i.e. the orphan has been
   * continuously observed for at least 30 minutes. Time-based instead of count-based because the
   * count source freezes at 1 once debounce suppression kicks in (every tick after the first is
   * suppressed and writes no new audit row), so a count threshold never trips. The escalation also
   * requires {@code countPrior*OrphanOngoing == 0} to enforce once-per-debounce-window emission.
   */
  static final Duration ORPHAN_ESCALATION_WINDOW = Duration.ofMinutes(30);

  // Audit kinds
  private static final String KIND_RECON_STARTED = "ReconciliationStarted";
  private static final String KIND_RECON_COMPLETED = "ReconciliationCompleted";
  private static final String KIND_JOURNAL_ORPHAN = "JournalOrphan";
  private static final String KIND_BROKER_ORPHAN = "BrokerOrphan";
  // Issue #165 Phase 3: broker holds a position with no running PositionWorkflow.
  private static final String KIND_POSITION_ORPHAN = "PositionOrphan";
  // Issue #206: escalation kinds emitted when an orphan persists past the threshold.
  private static final String KIND_POSITION_ORPHAN_ONGOING = "PositionOrphanOngoing";
  private static final String KIND_JOURNAL_ORPHAN_ONGOING = "JournalOrphanOngoing";
  private static final String KIND_METRICS_RECORD_FAILED = "ReconciliationMetricsRecordFailed";
  // Plan-2A R-AA-4: a recon cycle issued an ABANDON-child AdoptionWorkflow start for an orphaned
  // FILLED position.
  private static final String KIND_RECON_AUTO_ADOPTION_INITIATED = "ReconAutoAdoptionInitiated";

  // Plan-2A R-AA-4: recon.auto_adopt.{initiated,already_owned,refused_not_held} metric outcomes.
  private static final String AUTO_ADOPT_INITIATED = "initiated";
  private static final String AUTO_ADOPT_ALREADY_OWNED = "already_owned";
  private static final String AUTO_ADOPT_REFUSED_NOT_HELD = "refused_not_held";

  private static final ActivityOptions DEFAULT_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();

  /**
   * Issue #89: metrics Activity must be non-fatal — a metrics outage cannot break the Phase 7 gate
   * signal source. 5s start-to-close + maximumAttempts=1 keeps it out of the critical path; on
   * failure the workflow swallows + audit-logs {@code ReconciliationMetricsRecordFailed} and still
   * returns its {@code ReconciliationSummary}.
   */
  private static final ActivityOptions METRICS_OPTIONS =
      ActivityOptions.newBuilder()
          .setStartToCloseTimeout(Duration.ofSeconds(5))
          .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
          .build();

  private final AuditActivities audit =
      Workflow.newActivityStub(AuditActivities.class, DEFAULT_OPTIONS);

  /**
   * Issue #206: read-only debounce lookups against {@code audit_log}. The Impl is fail-soft
   * (catches {@link RuntimeException} and returns 0/null per its docstrings), so the activity NEVER
   * throws to the workflow — Temporal's default unbounded retry policy is dead weight that bloats
   * history on transient DB hiccups. Bound to {@code maximumAttempts=1} like {@link
   * #METRICS_OPTIONS}.
   */
  private static final ActivityOptions AUDIT_QUERY_OPTIONS =
      ActivityOptions.newBuilder()
          .setStartToCloseTimeout(Duration.ofSeconds(5))
          .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
          .build();

  // Issue #206: read-side audit lookups for orphan debounce + escalation. Cron-fresh workflows
  // cannot carry state across ticks; we derive prior detections from audit_log queries.
  private final AuditQueryActivities auditQuery =
      Workflow.newActivityStub(AuditQueryActivities.class, AUDIT_QUERY_OPTIONS);

  private final ReconciliationMetricsActivities metrics =
      Workflow.newActivityStub(ReconciliationMetricsActivities.class, METRICS_OPTIONS);

  // Issue #165 Phase 3: orchestrator-local probe — workflowClient access is only available on the
  // orchestrator-svc worker, so the stub uses default options (core task queue).
  private final PositionLookupActivities positionLookup =
      Workflow.newActivityStub(PositionLookupActivities.class, DEFAULT_OPTIONS);

  private ReconciliationWorkflowInput input;

  @Override
  public ReconciliationSummary run(ReconciliationWorkflowInput in) {
    if (in.getSchemaVersion() == null || in.getSchemaVersion() > 1L) {
      throw new IllegalArgumentException(
          "ReconciliationWorkflowInput schema_version unsupported: " + in.getSchemaVersion());
    }
    this.input = in;

    // Issue #89: capture cycle start time (Workflow.currentTimeMillis is deterministic-safe)
    // immediately after the schema-version check so the lag duration covers the entire workflow
    // body — journal dump, broker list, orphan classification, audit emission.
    long cycleStartMillis = Workflow.currentTimeMillis();

    // Unwrap broker_target defensively: schema forbids null, but hand-crafted replays / test
    // fixtures can still arrive null. Pass it through so the factory's null/blank check raises
    // a non-retryable InvalidBrokerTargetError instead of NPEing inside the workflow body.
    String brokerTarget = in.getBrokerTarget() == null ? null : in.getBrokerTarget().value();

    ReconciliationExecActivity exec =
        Workflow.newActivityStub(
            ReconciliationExecActivity.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(ExecActivitiesFactory.taskQueueFor(brokerTarget))
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .build());

    auditLog(KIND_RECON_STARTED, subject("broker_target", brokerTarget));

    List<JournalEntry> journal = exec.journalDumpOpen(in.getTenantId(), in.getStrategyId());
    List<BrokerOpenOrder> brokerOpen = exec.brokerListOpenOrders();

    Set<String> brokerClientIds = new LinkedHashSet<>();
    for (BrokerOpenOrder o : brokerOpen) {
      brokerClientIds.add(o.getClientOrderId());
    }

    OffsetDateTime now = workflowNow();
    OffsetDateTime debounceSince = now.minus(ORPHAN_DEBOUNCE_WINDOW);
    long journalOrphans = 0;
    for (JournalEntry e : journal) {
      // Issue #295: the broker reports the bounded client_order_id (not the intent_key), and the
      // journal row carries the SAME bounded value in its client_order_id column — so the
      // journal↔broker correlation key is the client_order_id on both sides, NOT the intent_key.
      if (brokerClientIds.contains(e.getClientOrderId())) {
        continue;
      }
      OffsetDateTime recorded = e.getRecordedAt();
      long staleSecs =
          recorded == null ? Long.MAX_VALUE : Duration.between(recorded, now).getSeconds();
      if (staleSecs > JOURNAL_ORPHAN_STALE.getSeconds()) {
        // Issue #206: debounce — count prior detections of THIS intent_key within the window.
        long priorCount =
            auditQuery.countPriorJournalOrphans(
                in.getTenantId(), in.getStrategyId(), e.getIntentKey(), debounceSince);
        if (priorCount == 0L) {
          // First sighting in the window — emit the standard per-cycle JournalOrphan.
          auditLog(
              KIND_JOURNAL_ORPHAN,
              subject(
                  "intent_key",
                  e.getIntentKey(),
                  "state",
                  e.getState() == null ? null : e.getState().value(),
                  "stale_secs",
                  staleSecs,
                  "option_symbol",
                  e.getOptionSymbol()));
        } else {
          // Issue #219: suppressed-by-debounce path (priorCount >= 1). Time-based escalation —
          // fetch the earliest matching row and emit JournalOrphanOngoing iff it's older than the
          // escalation window AND no prior Ongoing row exists in the debounce window.
          OffsetDateTime firstSeen =
              auditQuery.firstSeenJournalOrphan(
                  in.getTenantId(), in.getStrategyId(), e.getIntentKey(), debounceSince);
          if (firstSeen != null
              && Duration.between(firstSeen, now).compareTo(ORPHAN_ESCALATION_WINDOW) >= 0) {
            long priorOngoing =
                auditQuery.countPriorJournalOrphanOngoing(
                    in.getTenantId(), in.getStrategyId(), e.getIntentKey(), debounceSince);
            if (priorOngoing == 0L) {
              // First escalation in this debounce window — emit the one-time JournalOrphanOngoing
              // signal. Subsequent ticks within the window stay silent because priorOngoing will
              // be >= 1 on the next look.
              long ageSecs = Duration.between(firstSeen, now).getSeconds();
              auditLog(
                  KIND_JOURNAL_ORPHAN_ONGOING,
                  subject(
                      "intent_key",
                      e.getIntentKey(),
                      "state",
                      e.getState() == null ? null : e.getState().value(),
                      "option_symbol",
                      e.getOptionSymbol(),
                      "first_seen_at",
                      firstSeen.toString(),
                      "last_seen_at",
                      now.toString(),
                      "age_secs",
                      ageSecs));
            }
            // Else: already escalated this window — silent suppress.
          }
          // Else: still within the escalation window — silent suppress (debounced).
        }
        journalOrphans++;
      }
    }

    // Issue #295: match broker open orders to the journal by client_order_id (the bounded value the
    // broker echoes, persisted in the journal's client_order_id column) — not the intent_key, which
    // the broker no longer sees.
    Set<String> journalClientOrderIds = new LinkedHashSet<>();
    for (JournalEntry e : journal) {
      journalClientOrderIds.add(e.getClientOrderId());
    }

    long brokerOrphans = 0;
    for (BrokerOpenOrder o : brokerOpen) {
      if (journalClientOrderIds.contains(o.getClientOrderId())) {
        continue;
      }
      // v0: stale_hours unknown (no open-at from broker contract) — document via the audit's
      // omission and revisit when the broker contract carries an open-at timestamp.
      auditLog(
          KIND_BROKER_ORPHAN,
          subject(
              "broker_order_id", o.getBrokerOrderId(),
              "client_order_id", o.getClientOrderId(),
              "option_symbol", o.getOptionSymbol(),
              "broker_state", o.getState()));
      brokerOrphans++;
    }

    // Issue #165 Phase 3: broker-held positions with no running PositionWorkflow. The orchestrator
    // never spawned (or already lost) the workflow that should be managing this OCC — most often
    // because a cancel-on-filled race orphaned the position before Phase 1/2 landed, or because a
    // CopytradeSignalWorkflow died after place + before startPositionWorkflow. Detect-only: v1
    // emits a PositionOrphan + count and stops there; auto-adoption is a follow-up.
    List<BrokerPosition> brokerPositions =
        exec.brokerListOpenPositions(in.getTenantId(), in.getStrategyId());
    long positionOrphans = 0;
    for (BrokerPosition p : brokerPositions) {
      List<JournalEntry> filled =
          exec.journalListFilledByOcc(in.getTenantId(), in.getStrategyId(), p.getOptionSymbol());
      if (filled.isEmpty()) {
        // No FILLED journal row for this OCC — stronger orphan signal. We can't rebuild the
        // expected PositionWorkflow id because there is no entry_signal_id to anchor it on.
        emitPositionOrphanWithDebounce(
            in, p, /* expectedWfId= */ null, /* signalId= */ null, "missing", now, debounceSince);
        positionOrphans++;
        continue;
      }
      JournalEntry recentFilled = filled.get(0);
      // Issue #432: anchor the owner check on the OCC, NOT a reconstructed signal_id. After a
      // partial SELL, filled.get(0) is the EXIT order, whose signal_id never named the live
      // PositionWorkflow (pos/<occ>/<entrySignalId>) — rebuilding the id from it false-fires a
      // PositionOrphan on every partial exit and maybeAutoAdopt spawns a DUPLICATE PositionWorkflow
      // for the broker's remaining qty (one phantom per partial exit). findPositionWorkflowId
      // answers "is ANY live PositionWorkflow on this OCC?" via the padded-OCC cache/Visibility
      // key,
      // independent of signal_id (the cache→Visibility resolution detail is documented on the
      // activity itself). Pass the journal row's padded canonical option_symbol, not the broker's
      // (possibly compact) p.getOptionSymbol(): the cache key + ContractSymbol search attribute are
      // registered under the padded form (CopytradeSignalWorkflowImpl / AdoptionWorkflowImpl).
      String occ = recentFilled.getOptionSymbol();
      String ownerWfId =
          positionLookup.findPositionWorkflowId(in.getTenantId(), in.getStrategyId(), occ);
      // Confirm-running guards a stale cache pointing at a since-completed PositionWorkflow.
      boolean ownerRunning =
          ownerWfId != null && positionLookup.isPositionWorkflowRunning(ownerWfId);
      if (!ownerRunning) {
        // Genuine orphan: a FILLED journal anchor exists but no running PositionWorkflow manages
        // the
        // OCC (e.g. crash after place / before startPositionWorkflow → never cached, Visibility
        // finds
        // nothing → ownerWfId is null). emitPositionOrphanWithDebounce tolerates a null ownerWfId.
        emitPositionOrphanWithDebounce(
            in, p, ownerWfId, recentFilled.getSignalId(), "filled", now, debounceSince);
        positionOrphans++;
        // Plan-2A R-AA-4: a FILLED journal anchor exists (this branch) AND no running owner →
        // auto-adopt the orphaned-but-legit lot by starting AdoptionWorkflow as an ABANDON child.
        // The "missing"/no-anchor branch above intentionally falls through (page only) and never
        // reaches here. No recon-side version gate: recon executions are short-lived per scheduled
        // run (workflowId carries {{.ScheduledRunID}}), so there is no long-lived in-flight history
        // to replay-protect — a getVersion marker here would be vacuous.
        maybeAutoAdopt(in, brokerTarget, p, occ, brokerOpen);
      }
    }

    ReconciliationSummary summary = new ReconciliationSummary();
    summary.setSchemaVersion(1L);
    summary.setJournalEntriesChecked((long) journal.size());
    summary.setBrokerOrdersChecked((long) brokerOpen.size());
    summary.setJournalOrphans(journalOrphans);
    summary.setBrokerOrphans(brokerOrphans);
    summary.setPositionOrphans(positionOrphans);

    auditLog(
        KIND_RECON_COMPLETED,
        subject(
            "broker_target", brokerTarget,
            "journal_entries_checked", summary.getJournalEntriesChecked(),
            "broker_orders_checked", summary.getBrokerOrdersChecked(),
            "journal_orphans", journalOrphans,
            "broker_orphans", brokerOrphans,
            "position_orphans", positionOrphans));

    // Issue #89: record per-cycle Micrometer metrics for the Phase 7 live-promotion gate.
    // Wrapped in try/catch so a metrics outage cannot fail a reconciliation cycle — the gate
    // operator can fall back to the audit-log-derived SQL documented in
    // docs/ops/reconciliation-metrics.md.
    long lagMillis = Workflow.currentTimeMillis() - cycleStartMillis;
    long discrepancies = journalOrphans + brokerOrphans + positionOrphans;
    long intentsReconciled = journal.size();
    try {
      metrics.recordCycle(
          in.getTenantId(),
          in.getStrategyId(),
          brokerTarget,
          lagMillis,
          discrepancies,
          intentsReconciled);
    } catch (RuntimeException e) {
      auditLog(
          KIND_METRICS_RECORD_FAILED,
          subject(
              "broker_target", brokerTarget,
              "lag_millis", lagMillis,
              "discrepancies", discrepancies,
              "intents_reconciled", intentsReconciled,
              "error_class", e.getClass().getName(),
              "error_message", e.getMessage()));
    }
    return summary;
  }

  /**
   * Issue #206: emit a PositionOrphan with 1h debounce. Suppresses re-emission within the window
   * and emits a one-time {@code PositionOrphanOngoing} escalation when prior+1 reaches the
   * threshold. Debounce key is {@code option_symbol + journal_status} (so a "missing" → "filled"
   * flip emits a fresh first-detection rather than being silently debounced).
   */
  private void emitPositionOrphanWithDebounce(
      ReconciliationWorkflowInput in,
      BrokerPosition p,
      String expectedWfId,
      String signalId,
      String journalStatus,
      OffsetDateTime now,
      OffsetDateTime debounceSince) {
    long priorCount =
        auditQuery.countPriorPositionOrphans(
            in.getTenantId(),
            in.getStrategyId(),
            p.getOptionSymbol(),
            journalStatus,
            debounceSince);
    if (priorCount == 0L) {
      Map<String, Object> subj =
          subject(
              "option_symbol",
              p.getOptionSymbol(),
              "qty",
              p.getQty(),
              "expected_workflow_id",
              expectedWfId,
              "journal_status",
              journalStatus);
      if (signalId != null) {
        subj.put("journal_entry_signal_id", signalId);
      }
      auditLog(KIND_POSITION_ORPHAN, subj);
    } else {
      // Issue #219: suppressed-by-debounce path (priorCount >= 1). Time-based escalation — fetch
      // the earliest matching row and emit PositionOrphanOngoing iff it's older than the
      // escalation window AND no prior Ongoing row exists in the debounce window.
      OffsetDateTime firstSeen =
          auditQuery.firstSeenPositionOrphan(
              in.getTenantId(),
              in.getStrategyId(),
              p.getOptionSymbol(),
              journalStatus,
              debounceSince);
      if (firstSeen != null
          && Duration.between(firstSeen, now).compareTo(ORPHAN_ESCALATION_WINDOW) >= 0) {
        long priorOngoing =
            auditQuery.countPriorPositionOrphanOngoing(
                in.getTenantId(),
                in.getStrategyId(),
                p.getOptionSymbol(),
                journalStatus,
                debounceSince);
        if (priorOngoing == 0L) {
          // First escalation in this debounce window — emit the one-time PositionOrphanOngoing
          // signal. Subsequent ticks within the window stay silent because priorOngoing >= 1.
          long ageSecs = Duration.between(firstSeen, now).getSeconds();
          Map<String, Object> subj =
              subject(
                  "option_symbol",
                  p.getOptionSymbol(),
                  "qty",
                  p.getQty(),
                  "expected_workflow_id",
                  expectedWfId,
                  "journal_status",
                  journalStatus,
                  "first_seen_at",
                  firstSeen.toString(),
                  "last_seen_at",
                  now.toString(),
                  "age_secs",
                  ageSecs);
          if (signalId != null) {
            subj.put("journal_entry_signal_id", signalId);
          }
          auditLog(KIND_POSITION_ORPHAN_ONGOING, subj);
        }
        // Else: already escalated this window — silent suppress.
      }
      // Else: still within the escalation window — silent suppress (debounced).
    }
  }

  /**
   * Plan-2A R-AA-4: auto-adopt an orphaned FILLED position. Caller has already established that (a)
   * a FILLED journal anchor exists for the OCC and (b) no running PositionWorkflow manages the OCC
   * (per {@code findPositionWorkflowId} — Issue #432) — that is the very condition that fires the
   * {@code journal_status='filled'} PositionOrphan. This method adds the over-sell gate +
   * idempotency precheck, then starts {@code AdoptionWorkflow} as a non-blocking ABANDON child.
   * {@code occ} is the journal row's padded canonical option_symbol (the cache / ContractSymbol key
   * — NOT the broker's possibly-compact {@code p.getOptionSymbol()}); the precheck re-resolves the
   * owner authoritatively by that OCC.
   *
   * <p>Mechanism (fixed by the plan, mirrors {@code AdoptionWorkflowImpl} ~152-161):
   *
   * <ul>
   *   <li>{@code Workflow.newChildWorkflowStub} + {@code ChildWorkflowOptions} with the canonical
   *       {@code WorkflowIds.adoption(tenant,strategy,occ)} id, {@code ParentClosePolicy.ABANDON}
   *       (the child must outlive this short recon cycle), and {@code
   *       WorkflowIdReusePolicy.ALLOW_DUPLICATE} (NOT REJECT_DUPLICATE — the adoption id keys on
   *       (tenant,strategy,occ) only, so REJECT would permanently block re-adopting an OCC that
   *       goes adopted → managed → re-orphaned; the precheck below covers churn).
   *   <li>Launched via {@code Async.function(child::adopt, input)} so recon does not block.
   *   <li>NOT {@code ExternalWorkflowStub.start()} (no such method) and NOT a client-side {@code
   *       WorkflowExecutionAlreadyStarted} catch.
   * </ul>
   *
   * <p>Idempotency = recon-side PRECHECK before the Async start: re-resolve the PositionWorkflow
   * owner by OCC and skip if that owner OR the adoption id is already running. This covers both the
   * in-flight and post-complete windows. The residual sub-second TOCTOU child-already-started is a
   * benign no-op — the failed child-start Promise is swallowed (NOT propagated to recon {@code
   * run()}).
   *
   * <p>Over-sell gate: only auto-adopt when no open/pending SELL for the OCC exists at the broker.
   * Matched on {@code side==SELL} with the OCC normalized via {@link OccSymbol#compact(String)} on
   * BOTH sides (padded-vs-compact mismatch would defeat the gate, cf. the %20-padding bug in
   * 16e4c6e). NOTE: {@code AlpacaPaperBroker} does not override {@code listOpenOrders()} today, so
   * on Alpaca {@code brokerOpen} is empty and this gate is currently inert — R-AA-1 (workflow stays
   * running until a broker-confirmed fill) is the real settling-close defense. Implemented
   * correctly so it works once {@code listOpenOrders()} lands.
   */
  private void maybeAutoAdopt(
      ReconciliationWorkflowInput in,
      String brokerTarget,
      BrokerPosition p,
      String occ,
      List<BrokerOpenOrder> brokerOpen) {
    String adoptWfId =
        WorkflowIds.adoption(in.getTenantId(), in.getStrategyId(), p.getOptionSymbol());

    // Over-sell gate (b): refuse if any open/pending SELL for this OCC exists at the broker.
    String compactOcc = OccSymbol.compact(p.getOptionSymbol());
    for (BrokerOpenOrder o : brokerOpen) {
      if (o.getSide() == BrokerOpenOrder.Side.SELL
          && compactOcc != null
          && compactOcc.equals(OccSymbol.compact(o.getOptionSymbol()))) {
        recordAutoAdoptMetric(in, brokerTarget, AUTO_ADOPT_REFUSED_NOT_HELD);
        return;
      }
    }

    // Issue #432: authoritative idempotency precheck before the Async start. Re-resolve the owner
    // by
    // the canonical OCC (a fresh read closes the TOCTOU window between the caller's loop check and
    // this write side-effect — a concurrent recon tick or adoption may have created/completed the
    // owner since) and skip if that owner OR the adoption id is already running. A running adoption
    // id means a prior cycle already issued the start — skip to avoid a duplicate.
    String ownerWfId =
        positionLookup.findPositionWorkflowId(in.getTenantId(), in.getStrategyId(), occ);
    if ((ownerWfId != null && positionLookup.isPositionWorkflowRunning(ownerWfId))
        || positionLookup.isPositionWorkflowRunning(adoptWfId)) {
      recordAutoAdoptMetric(in, brokerTarget, AUTO_ADOPT_ALREADY_OWNED);
      return;
    }

    AdoptionWorkflowInput adoptInput = new AdoptionWorkflowInput();
    adoptInput.setSchemaVersion(1L);
    adoptInput.setTenantId(in.getTenantId());
    adoptInput.setStrategyId(in.getStrategyId());
    adoptInput.setOcc(p.getOptionSymbol());
    adoptInput.setOperatorId("recon");

    ChildWorkflowOptions opts =
        ChildWorkflowOptions.newBuilder()
            .setWorkflowId(adoptWfId)
            .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .build();
    AdoptionWorkflow child = Workflow.newChildWorkflowStub(AdoptionWorkflow.class, opts);

    // Emit provenance + counter BEFORE the Async start (the start is the side effect this audits).
    Map<String, Object> subj =
        subject(
            "option_symbol",
            p.getOptionSymbol(),
            "qty",
            p.getQty(),
            "adoption_workflow_id",
            adoptWfId,
            "expected_workflow_id",
            ownerWfId);
    auditLog(KIND_RECON_AUTO_ADOPTION_INITIATED, subj);
    recordAutoAdoptMetric(in, brokerTarget, AUTO_ADOPT_INITIATED);

    // Non-blocking start so recon doesn't wait on the adoption. The residual TOCTOU
    // child-already-started is a benign no-op: swallow the failed child-start Promise so it never
    // propagates to recon run(). This is the deterministic Promise.exceptionally handler — distinct
    // from the forbidden client-side WorkflowExecutionAlreadyStarted catch.
    Promise<?> started = Async.function(child::adopt, adoptInput);
    started.exceptionally(t -> null);
  }

  private void recordAutoAdoptMetric(
      ReconciliationWorkflowInput in, String brokerTarget, String outcome) {
    try {
      metrics.recordAutoAdopt(in.getTenantId(), in.getStrategyId(), brokerTarget, outcome);
    } catch (RuntimeException e) {
      // Metrics are non-fatal (mirrors the recordCycle convention) — a meter outage cannot break a
      // recon cycle. Swallow; the audit row is the durable record of the decision.
    }
  }

  private void auditLog(String kind, Map<String, Object> subject) {
    audit.log(auditEvent(kind, subject));
  }

  private AuditEvent auditEvent(String kind, Map<String, ?> subject) {
    AuditEvent e = new AuditEvent();
    e.setSchemaVersion(1L);
    e.setTenantId(input.getTenantId());
    e.setStrategyId(input.getStrategyId());
    e.setEventId(Workflow.randomUUID().toString());
    e.setOccurredAt(workflowNow());
    e.setKind(kind);
    e.setSubject(new LinkedHashMap<>(subject));
    e.setActor("workflow:ReconciliationWorkflow");
    e.setWorkflowId(Workflow.getInfo().getWorkflowId());
    e.setCorrelationId(input.getTenantId() + "/" + input.getStrategyId());
    return e;
  }

  private static Map<String, Object> subject(Object... kv) {
    if ((kv.length & 1) != 0) {
      throw new IllegalArgumentException("subject() requires an even number of key/value args");
    }
    Map<String, Object> m = new LinkedHashMap<>(kv.length);
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private static OffsetDateTime workflowNow() {
    return OffsetDateTime.ofInstant(
        Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC);
  }
}
