package com.ohmytradeagent.orchestrator.config;

import com.ohmytradeagent.orchestrator.activities.AccountSnapshotMetricsActivities;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.AuditQueryActivities;
import com.ohmytradeagent.orchestrator.activities.BrokerCredentialAuditActivities;
import com.ohmytradeagent.orchestrator.activities.ContractActivities;
import com.ohmytradeagent.orchestrator.activities.DailyPnlActivities;
import com.ohmytradeagent.orchestrator.activities.KillSwitchCascadeActivities;
import com.ohmytradeagent.orchestrator.activities.LivePromotionActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.activities.ReconciliationMetricsActivities;
import com.ohmytradeagent.orchestrator.activities.RiskActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.activities.WatchlistMirrorActivities;
import com.ohmytradeagent.orchestrator.workflows.AccountSnapshotWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.AdoptionWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.BrokerCredentialAuditWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.CopytradeSignalWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.PositionWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.ReconciliationWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.WatchlistMirrorWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TemporalWorkerConfig {

  @Value("${temporal.target:localhost:7233}")
  private String target;

  @Value("${temporal.namespace:default}")
  private String namespace;

  @Value("${temporal.task-queue:orchestrator-core}")
  private String taskQueue;

  @Bean
  public Clock systemClock() {
    return Clock.systemUTC();
  }

  @Bean
  public WorkflowServiceStubs workflowServiceStubs() {
    return WorkflowServiceStubs.newServiceStubs(
        WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
  }

  @Bean
  public WorkflowClient workflowClient(WorkflowServiceStubs service) {
    return WorkflowClient.newInstance(
        service, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
  }

  @Bean
  public WorkerFactory workerFactory(WorkflowClient client) {
    return WorkerFactory.newInstance(client);
  }

  @Bean
  public Worker worker(
      WorkerFactory factory,
      AuditActivities audit,
      AuditQueryActivities auditQuery,
      BrokerCredentialAuditActivities brokerCredentialAudit,
      StrategyActivities strategy,
      RiskActivities risk,
      ContractActivities contract,
      PositionLookupActivities positionLookup,
      MarketCalendarActivities calendar,
      KillSwitchCascadeActivities cascade,
      DailyPnlActivities dailyPnl,
      LivePromotionActivities livePromotion,
      ReconciliationMetricsActivities reconciliationMetrics,
      AccountSnapshotMetricsActivities accountSnapshotMetrics,
      WatchlistMirrorActivities watchlistMirror) {
    Worker worker = factory.newWorker(taskQueue);
    // Issue #239/#285: AdoptionWorkflow is the operator-triggered orphan-adoption entry point. It
    // runs as a workflow (not an in-process Activity) so its broker-truth
    // ReconciliationExecActivity
    // calls route through the exec task queue (broker-<target>), exactly like
    // ReconciliationWorkflow
    // — no in-process exec bean / throwing placeholder is needed.
    worker.registerWorkflowImplementationTypes(
        CopytradeSignalWorkflowImpl.class,
        PositionWorkflowImpl.class,
        KillSwitchWorkflowImpl.class,
        ReconciliationWorkflowImpl.class,
        AdoptionWorkflowImpl.class,
        // Started synchronously by the tenant-dashboard BFF to read broker-account equity; the
        // workflow dispatches AccountSnapshotActivity to broker-<target> (a Temporal client cannot
        // dispatch an Activity directly).
        AccountSnapshotWorkflowImpl.class,
        // Net-new single-step workflow: mirrors the verbatim daily watchlist to the trade-alert
        // Discord webhook. The signal-source-discord sidecar starts it by the type name
        // "WatchlistMirrorWorkflow" on this same orchestrator-core queue.
        WatchlistMirrorWorkflowImpl.class,
        // UI-P2-a credential-audit carrier: short-lived workflow that hosts the
        // (already-registered)
        // metadata-only BrokerCredentialAuditActivities.record and completes. Started by the
        // api-gateway /broker-credentials forward; the activity impl is registered below (do NOT
        // re-register it).
        BrokerCredentialAuditWorkflowImpl.class);
    worker.registerActivitiesImplementations(
        audit,
        auditQuery,
        // P6-d (multi-tenant-broker-credentials): DARK metadata-only credential-write audit
        // capability. Registered so its wiring + determinism are proven; nothing calls it in P6-d
        // (carrier + api-gateway caller defer to UI-P2).
        brokerCredentialAudit,
        strategy,
        risk,
        contract,
        positionLookup,
        calendar,
        cascade,
        dailyPnl,
        livePromotion,
        reconciliationMetrics,
        accountSnapshotMetrics,
        watchlistMirror);
    return worker;
  }
}
