package com.ohmytradeagent.marketdata.config;

import com.ohmytradeagent.contract.activities.SubscribePremiumActivity;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TemporalWorkerConfig {

  @Value("${temporal.target:localhost:7233}")
  private String target;

  @Value("${temporal.namespace:default}")
  private String namespace;

  @Value("${temporal.task-queue:market-data}")
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

  /**
   * Single-threaded dispatcher for tick fan-out. Keeps the (potentially feed-driven) stream
   * callback decoupled from Temporal RPC latency.
   */
  @Bean
  public ExecutorService tickDispatcher() {
    return Executors.newSingleThreadExecutor(
        r -> {
          Thread t = new Thread(r, "market-data-tick-dispatch");
          t.setDaemon(true);
          return t;
        });
  }

  @Bean
  public Worker worker(WorkerFactory factory, SubscribePremiumActivity activity) {
    Worker worker = factory.newWorker(taskQueue);
    worker.registerActivitiesImplementations(activity);
    return worker;
  }
}
