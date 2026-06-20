package com.ohmytradeagent.exec.config;

import com.ohmytradeagent.contract.activities.AccountSnapshotActivity;
import com.ohmytradeagent.contract.activities.MarketCalendarActivity;
import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import com.ohmytradeagent.contract.activities.ReconciliationExecActivity;
import com.ohmytradeagent.exec.activities.ExecActivities;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TemporalWorkerConfig {

  @Value("${temporal.target:localhost:7233}")
  private String target;

  @Value("${temporal.namespace:default}")
  private String namespace;

  // Phase 2c.2: default to broker-alpaca-paper to match the orchestrator-side routing for
  // StrategyConfig.broker_target=alpaca-paper. Operators of other brokers override via
  // TEMPORAL_TASK_QUEUE (see infra/k8s/52-exec-alpaca-paper.yaml for the env-driven pattern).
  @Value("${temporal.task-queue:broker-alpaca-paper}")
  private String taskQueue;

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
      ExecActivities exec,
      ReconciliationExecActivity recon,
      PreTradeCheckActivity preTradeCheck,
      AccountSnapshotActivity accountSnapshot,
      MarketCalendarActivity marketCalendar) {
    Worker worker = factory.newWorker(taskQueue);
    worker.registerActivitiesImplementations(
        exec, recon, preTradeCheck, accountSnapshot, marketCalendar);
    return worker;
  }
}
