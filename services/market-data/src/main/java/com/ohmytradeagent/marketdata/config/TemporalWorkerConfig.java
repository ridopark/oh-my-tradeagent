package com.ohmytradeagent.marketdata.config;

import com.ohmytradeagent.contract.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.contract.activities.SubscribeEquityActivity;
import com.ohmytradeagent.contract.activities.SubscribePremiumActivity;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
  // @Primary so ExecutorService injections (the tick-fan-out dispatcher in both the premium and
  // equity activities) resolve to this bean and not the ScheduledExecutorService watchdog below
  // (which is also an ExecutorService). The watchdog injects by its narrower
  // ScheduledExecutorService
  // type, so it stays unambiguous.
  @Bean
  @Primary
  public ExecutorService tickDispatcher() {
    return Executors.newSingleThreadExecutor(
        r -> {
          Thread t = new Thread(r, "market-data-tick-dispatch");
          t.setDaemon(true);
          return t;
        });
  }

  /**
   * Scheduled executor for the equity-subscription dead-feed watchdog (one periodic liveness task
   * per active subscription). Distinct from {@link #tickDispatcher()} (which fans ticks out) so a
   * stalled watchdog tick can never block tick dispatch and vice versa.
   */
  @Bean
  public ScheduledExecutorService equityFeedWatchdog() {
    return Executors.newSingleThreadScheduledExecutor(
        r -> {
          Thread t = new Thread(r, "market-data-equity-watchdog");
          t.setDaemon(true);
          return t;
        });
  }

  @Bean
  public Worker worker(
      WorkerFactory factory,
      SubscribePremiumActivity subscribePremiumActivity,
      GetOptionQuoteActivity getOptionQuoteActivity,
      SubscribeEquityActivity subscribeEquityActivity) {
    Worker worker = factory.newWorker(taskQueue);
    worker.registerActivitiesImplementations(
        subscribePremiumActivity, getOptionQuoteActivity, subscribeEquityActivity);
    return worker;
  }
}
