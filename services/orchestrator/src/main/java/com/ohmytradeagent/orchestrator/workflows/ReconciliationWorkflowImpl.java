package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.BrokerOpenOrder;
import com.ohmytradeagent.contract.JournalEntry;
import com.ohmytradeagent.contract.ReconciliationSummary;
import com.ohmytradeagent.contract.ReconciliationWorkflowInput;
import com.ohmytradeagent.contract.activities.ReconciliationExecActivity;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
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

  // Audit kinds
  private static final String KIND_RECON_STARTED = "ReconciliationStarted";
  private static final String KIND_RECON_COMPLETED = "ReconciliationCompleted";
  private static final String KIND_JOURNAL_ORPHAN = "JournalOrphan";
  private static final String KIND_BROKER_ORPHAN = "BrokerOrphan";
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

  private final ReconciliationMetricsActivities metrics =
      Workflow.newActivityStub(ReconciliationMetricsActivities.class, METRICS_OPTIONS);

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
    long journalOrphans = 0;
    for (JournalEntry e : journal) {
      if (brokerClientIds.contains(e.getIntentKey())) {
        continue;
      }
      OffsetDateTime recorded = e.getRecordedAt();
      long staleSecs =
          recorded == null ? Long.MAX_VALUE : Duration.between(recorded, now).getSeconds();
      if (staleSecs > JOURNAL_ORPHAN_STALE.getSeconds()) {
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

    ReconciliationSummary summary = new ReconciliationSummary();
    summary.setSchemaVersion(1L);
    summary.setJournalEntriesChecked((long) journal.size());
    summary.setBrokerOrdersChecked((long) brokerOpen.size());
    summary.setJournalOrphans(journalOrphans);
    summary.setBrokerOrphans(brokerOrphans);

    auditLog(
        KIND_RECON_COMPLETED,
        subject(
            "broker_target", brokerTarget,
            "journal_entries_checked", summary.getJournalEntriesChecked(),
            "broker_orders_checked", summary.getBrokerOrdersChecked(),
            "journal_orphans", journalOrphans,
            "broker_orphans", brokerOrphans));

    // Issue #89: record per-cycle Micrometer metrics for the Phase 7 live-promotion gate.
    // Wrapped in try/catch so a metrics outage cannot fail a reconciliation cycle — the gate
    // operator can fall back to the audit-log-derived SQL documented in
    // docs/ops/reconciliation-metrics.md.
    long lagMillis = Workflow.currentTimeMillis() - cycleStartMillis;
    long discrepancies = journalOrphans + brokerOrphans;
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
