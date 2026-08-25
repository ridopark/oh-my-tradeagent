package com.ohmytradeagent.marketdata.config;

import com.ohmytradeagent.contract.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.contract.activities.SubscribeEquityActivity;
import com.ohmytradeagent.contract.activities.SubscribePremiumActivity;
import com.ohmytradeagent.contract.temporal.LenientDataConverter;
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
        service,
        WorkflowClientOptions.newBuilder()
            .setNamespace(namespace)
            // #772: lenient payload deserialization so a since-removed schema field in a recorded
            // history can never wedge a replay. Write-path schema validation stays strict.
            .setDataConverter(LenientDataConverter.instance())
            .build());
  }

  @Bean
  public WorkerFactory workerFactory(WorkflowClient client) {
    return WorkerFactory.newInstance(client);
  }

  /**
   * Single-threaded dispatcher for tick fan-out. Keeps the (potentially feed-driven) stream
   * callback decoupled from Temporal RPC latency.
   */
  // @Primary: the PREMIUM (options/chandelier) fan-out dispatcher. Unqualified ExecutorService
  // injections (SubscribePremiumActivityImpl) resolve here, not the ScheduledExecutorService
  // watchdog
  // below (which injects by its narrower type). The EQUITY activity injects equityTickDispatcher()
  // by
  // qualifier instead, so the equity feed (now that it actually delivers ticks) cannot starve
  // premium
  // dispatch on a shared single thread.
  @Bean
  @Primary
  public ExecutorService tickDispatcher() {
    return Executors.newSingleThreadExecutor(
        r -> {
          Thread t = new Thread(r, "market-data-premium-tick-dispatch");
          t.setDaemon(true);
          return t;
        });
  }

  /**
   * Dedicated fan-out dispatcher for the EQUITY (watchlist-trigger) feed, separate from the premium
   * dispatcher so a burst of equity ticks (or a slow equity signal RPC) cannot delay copytrade's
   * chandelier-tick dispatch. Injected into {@code SubscribeEquityActivityImpl} by qualifier.
   */
  @Bean
  public ExecutorService equityTickDispatcher() {
    return Executors.newSingleThreadExecutor(
        r -> {
          Thread t = new Thread(r, "market-data-equity-tick-dispatch");
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
