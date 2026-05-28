package com.ohmytradeagent.orchestrator.workflows;

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
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
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
      if (brokerClientIds.contains(e.getIntentKey())) {
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

    Set<String> journalIntentKeys = new LinkedHashSet<>();
    for (JournalEntry e : journal) {
      journalIntentKeys.add(e.getIntentKey());
    }

    long brokerOrphans = 0;
    for (BrokerOpenOrder o : brokerOpen) {
      if (journalIntentKeys.contains(o.getClientOrderId())) {
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
      // Issue #243: rebuild expected_workflow_id from the journal row's canonical option_symbol —
      // the padded 21-char OCC (OccSymbol.of pads the root to 6 chars with %-6s) that was also used
      // to build the live PositionWorkflow id at spawn (CopytradeSignalWorkflowImpl). The broker
      // reports the compact OCC (Alpaca strips the space-padding on order placement), so anchoring
      // on p.getOptionSymbol() would produce an id the running owner never registered under and
      // fire a false PositionOrphan even when the owner is alive.
      String expectedWfId =
          WorkflowIds.position(
              in.getTenantId(),
              in.getStrategyId(),
              recentFilled.getOptionSymbol(),
              recentFilled.getSignalId());
      if (!positionLookup.isPositionWorkflowRunning(expectedWfId)) {
        emitPositionOrphanWithDebounce(
            in, p, expectedWfId, recentFilled.getSignalId(), "filled", now, debounceSince);
        positionOrphans++;
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
