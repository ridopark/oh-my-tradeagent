package com.ohmytradeagent.orchestrator.config;

import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.AuditQueryActivities;
import com.ohmytradeagent.orchestrator.activities.ContractActivities;
import com.ohmytradeagent.orchestrator.activities.DailyPnlActivities;
import com.ohmytradeagent.orchestrator.activities.KillSwitchCascadeActivities;
import com.ohmytradeagent.orchestrator.activities.LivePromotionActivities;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.PositionAdoptionActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.activities.ReconciliationMetricsActivities;
import com.ohmytradeagent.orchestrator.activities.RiskActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.contract.activities.ReconciliationExecActivity;
import com.ohmytradeagent.orchestrator.workflows.CopytradeSignalWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.PositionWorkflowImpl;
import com.ohmytradeagent.orchestrator.workflows.ReconciliationWorkflowImpl;
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

  /**
   * Issue #239: {@link PositionAdoptionActivities} (registered on this worker) depends on the
   * cross-service {@link ReconciliationExecActivity} for broker truth + journal terminalization.
   * The orchestrator module only carries the contract interface — the impl lives in {@code
   * services/exec} on the {@code broker-<target>} task queue — and a plain Activity cannot create a
   * Temporal activity stub (those are workflow-only). The production caller of {@code
   * adoptOrphanPosition} is the deferred recon-loop auto-trigger / operator command, which will
   * route the exec calls through a workflow and supply the real broker-queue stub. Until that lands
   * this fallback bean satisfies Spring DI for the worker registration while failing loudly if
   * adoption is invoked before the trigger is wired. A {@code @Primary} broker-queue-backed bean
   * supplied by the follow-up overrides it.
   */
  @Bean
  public ReconciliationExecActivity reconciliationExecActivity() {
    return (ReconciliationExecActivity)
        java.lang.reflect.Proxy.newProxyInstance(
            ReconciliationExecActivity.class.getClassLoader(),
            new Class<?>[] {ReconciliationExecActivity.class},
            (proxy, method, args) -> {
              if (method.getDeclaringClass() == Object.class) {
                return method.invoke(this, args);
              }
              throw new UnsupportedOperationException(
                  "ReconciliationExecActivity is not wired for direct invocation from the"
                      + " orchestrator worker. Issue #239 adoption needs the deferred recon"
                      + " auto-trigger / operator command to route broker-queue calls through a"
                      + " workflow. Method invoked: "
                      + method.getName());
            });
  }

  @Bean
  public Worker worker(
      WorkerFactory factory,
      AuditActivities audit,
      AuditQueryActivities auditQuery,
      StrategyActivities strategy,
      RiskActivities risk,
      ContractActivities contract,
      PositionLookupActivities positionLookup,
      MarketCalendarActivities calendar,
      KillSwitchCascadeActivities cascade,
      DailyPnlActivities dailyPnl,
      LivePromotionActivities livePromotion,
      ReconciliationMetricsActivities reconciliationMetrics,
      PositionAdoptionActivities positionAdoption) {
    Worker worker = factory.newWorker(taskQueue);
    worker.registerWorkflowImplementationTypes(
        CopytradeSignalWorkflowImpl.class,
        PositionWorkflowImpl.class,
        KillSwitchWorkflowImpl.class,
        ReconciliationWorkflowImpl.class);
    worker.registerActivitiesImplementations(
        audit,
        auditQuery,
        strategy,
        risk,
        contract,
        positionLookup,
        calendar,
        cascade,
        dailyPnl,
        livePromotion,
        reconciliationMetrics,
        positionAdoption);
    return worker;
  }
}
