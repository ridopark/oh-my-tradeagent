package com.ohmytradeagent.orchestrator.config;

import com.ohmytradeagent.orchestrator.activities.AccountKillSwitchCascadeActivities;
import com.ohmytradeagent.orchestrator.activities.AccountPnlActivities;
import com.ohmytradeagent.orchestrator.activities.AccountSnapshotMetricsActivities;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.AuditQueryActivities;
import com.ohmytradeagent.orchestrator.activities.BrokerCredentialAuditActivities;
import com.ohmytradeagent.orchestrator.activities.ContractActivities;
import com.ohmytradeagent.orchestrator.activities.DailyPnlActivities;
import com.ohmytradeagent.orchestrator.activities.DefaultTriggerFireDecider;
import com.ohmytradeagent.orchestrator.activities.DefaultWatchlistEntryDecider;
import com.ohmytradeagent.orchestrator.activities.KillSwitchCascadeActivities;
import com.ohmytradeagent.orchestrator.activities.LivePromotionActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.activities.ReconciliationMetricsActivities;
import com.ohmytradeagent.orchestrator.activities.RiskActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyConfigUpdateActivities;
import com.ohmytradeagent.orchestrator.activities.TenantConfigActivities;
import com.ohmytradeagent.orchestrator.activities.WatchlistMirrorActivities;
import com.ohmytradeagent.orchestrator.activities.WatchlistTriggerActivities;
import com.ohmytradeagent.orchestrator.workflows.AccountKillSwitchWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.AccountSnapshotWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.AdoptionWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.BrokerCredentialAuditWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.CopytradeSignalWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.PositionSnapshotWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.PositionWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.ReconciliationWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.StrategyConfigUpdateWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.WatchlistMirrorWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.WatchlistTriggerSessionWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.WatchlistTriggerWorkflowImpl;
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
      StrategyConfigUpdateActivities strategyConfigUpdate,
      StrategyActivities strategy,
      RiskActivities risk,
      ContractActivities contract,
      PositionLookupActivities positionLookup,
      MarketCalendarActivities calendar,
      KillSwitchCascadeActivities cascade,
      DailyPnlActivities dailyPnl,
      TenantConfigActivities tenantConfig,
      AccountPnlActivities accountPnl,
      AccountKillSwitchCascadeActivities accountCascade,
      LivePromotionActivities livePromotion,
      ReconciliationMetricsActivities reconciliationMetrics,
      AccountSnapshotMetricsActivities accountSnapshotMetrics,
      WatchlistMirrorActivities watchlistMirror,
      WatchlistTriggerActivities watchlistTrigger) {
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
        // Phase 6: account-level (tenant-wide) loss cap. One per tenant, started by
        // KillSwitchBootstrapper alongside the per-(tenant,strategy) KillSwitchWorkflow.
        AccountKillSwitchWorkflowImpl.class,
        ReconciliationWorkflowImpl.class,
        AdoptionWorkflowImpl.class,
        // Started synchronously by the tenant-dashboard BFF to read broker-account equity; the
        // workflow dispatches AccountSnapshotActivity to broker-<target> (a Temporal client cannot
        // dispatch an Activity directly).
        AccountSnapshotWorkflowImpl.class,
        // Started synchronously by the tenant-dashboard BFF to read broker-held positions WITH live
        // marks (current price + today's/total unrealized P&L); the workflow dispatches
        // ReconciliationExecActivity.brokerListOpenPositions to broker-<target>, same client-can't-
        // dispatch-an-Activity reason as AccountSnapshotWorkflow.
        PositionSnapshotWorkflowImpl.class,
        // Net-new single-step workflow: mirrors the verbatim daily watchlist to the trade-alert
        // Discord webhook. The signal-source-discord sidecar starts it by the type name
        // "WatchlistMirrorWorkflow" on this same orchestrator-core queue.
        WatchlistMirrorWorkflowImpl.class,
        // UI-P2-a credential-audit carrier: short-lived workflow that hosts the
        // (already-registered)
        // metadata-only BrokerCredentialAuditActivities.record and completes. Started by the
        // api-gateway /broker-credentials forward; the activity impl is registered below (do NOT
        // re-register it).
        BrokerCredentialAuditWorkflowImpl.class,
        // UI-P3-b config-write carrier: short-lived workflow that dispatches the
        // StrategyConfigUpdateActivities.update Activity (in-process StrategyConfigWriter) on this
        // orchestrator-core queue and returns the coarse outcome. Started by the api-gateway
        // /strategy-config forward (dark-gated); the activity impl is registered below.
        StrategyConfigUpdateWorkflowImpl.class,
        // Watchlist-trigger strategy. The session parent is started by
        // WatchlistMirrorActivitiesImpl
        // on a clean watchlist parse (for the configured trigger strategy, when enabled); it
        // fans out one WatchlistTriggerWorkflow child per leg. Both run on this orchestrator-core
        // queue; their activity impls (parse + arm/fire deciders) are registered below.
        WatchlistTriggerSessionWorkflowImpl.class,
        WatchlistTriggerWorkflowImpl.class);
    worker.registerActivitiesImplementations(
        audit,
        auditQuery,
        // P6-d (multi-tenant-broker-credentials): DARK metadata-only credential-write audit
        // capability. Registered so its wiring + determinism are proven; nothing calls it in P6-d
        // (carrier + api-gateway caller defer to UI-P2).
        brokerCredentialAudit,
        // UI-P3-b: DARK reduce-or-hold-risk runtime config-write capability. Drives the in-process
        // StrategyConfigWriter and coarsens its exceptions into the result outcome enum. Nothing
        // calls it unless the api-gateway /strategy-config route is enabled (flag-gated, off by
        // default).
        strategyConfigUpdate,
        strategy,
        risk,
        contract,
        positionLookup,
        calendar,
        cascade,
        dailyPnl,
        // Phase 6 account-level loss cap activities (tenant-config read, tenant-wide PnL+open book,
        // account-scoped cascade).
        tenantConfig,
        accountPnl,
        accountCascade,
        livePromotion,
        reconciliationMetrics,
        accountSnapshotMetrics,
        watchlistMirror,
        // Watchlist-trigger activities: the parse activity (@Component) plus the arm/fire decision
        // hooks. The deciders are plain POJOs (default pass-through, no injected collaborators), so
        // they are instantiated inline rather than wired as Spring beans — a strategy-specific
        // decider would replace these here.
        watchlistTrigger,
        new DefaultWatchlistEntryDecider(),
        new DefaultTriggerFireDecider());
    return worker;
  }
}
