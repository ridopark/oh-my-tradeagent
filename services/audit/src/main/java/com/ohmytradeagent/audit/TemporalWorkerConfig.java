package com.ohmytradeagent.audit;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Temporal worker for audit-svc. Registers {@link CopytradeSignalWorkflow} on the
 * configured task queue. Service connects to Temporal via {@code temporal.target} and operates in
 * namespace {@code temporal.namespace}.
 *
 * <p>Phase 0 scope: registration only. No activities yet; subsequent phases add them.
 */
@Configuration
public class TemporalWorkerConfig {

  @Value("${temporal.target:localhost:7233}")
  private String target;

  @Value("${temporal.namespace:default}")
  private String namespace;

  @Value("${temporal.task-queue:orchestrator-core}")
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
  public Worker worker(WorkerFactory factory) {
    Worker worker = factory.newWorker(taskQueue);
    worker.registerWorkflowImplementationTypes(CopytradeSignalWorkflowImpl.class);
    return worker;
  }
}
