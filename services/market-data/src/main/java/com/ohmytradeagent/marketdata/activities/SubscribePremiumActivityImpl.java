package com.ohmytradeagent.marketdata.activities;

import com.ohmytradeagent.contract.PremiumTick;
import com.ohmytradeagent.contract.SubscribePremiumRequest;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.contract.activities.SubscribePremiumActivity;
import com.ohmytradeagent.marketdata.stream.PremiumStreamSource;
import com.ohmytradeagent.marketdata.stream.Subscription;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 4 worker-side implementation of {@link SubscribePremiumActivity}. Subscribes a tick
 * consumer on the configured {@link PremiumStreamSource} and signals each tick into the named
 * PositionWorkflow as {@code chandelierTick}.
 *
 * <p>Signal dispatch runs on a worker-owned executor to keep the stream callback (which may be
 * driven by a feed thread) decoupled from Temporal RPC latency.
 *
 * <p>{@code subscribePremium} swallows source-side exceptions and returns FAILED so the workflow
 * can audit and proceed without a trail (instead of going into Temporal retry).
 */
@Component
public class SubscribePremiumActivityImpl implements SubscribePremiumActivity {

  private static final Logger log = LoggerFactory.getLogger(SubscribePremiumActivityImpl.class);

  private final PremiumStreamSource source;
  private final WorkflowClient workflowClient;
  private final ExecutorService dispatcher;

  public SubscribePremiumActivityImpl(
      PremiumStreamSource source, WorkflowClient workflowClient, ExecutorService dispatcher) {
    this.source = source;
    this.workflowClient = workflowClient;
    this.dispatcher = dispatcher;
  }

  @Override
  public SubscribePremiumResult subscribePremium(SubscribePremiumRequest req) {
    SubscribePremiumResult result = new SubscribePremiumResult();
    result.setSchemaVersion(1L);
    result.setSubscribedAt(OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
    try {
      String posWfId = req.getPositionWorkflowId();
      // Subscription id is captured lazily for the unsubscribe-on-notfound path.
      final String[] subIdHolder = new String[1];
      Subscription sub =
          source.subscribe(
              req.getContractSymbol(),
              posWfId,
              tick -> dispatcher.submit(() -> dispatchTick(posWfId, subIdHolder[0], tick)));
      subIdHolder[0] = sub.subscriptionId();
      result.setSubscriptionId(sub.subscriptionId());
      result.setStatus(SubscribePremiumResult.Status.SUBSCRIBED);
      return result;
    } catch (RuntimeException e) {
      log.warn(
          "subscribePremium failed for tenant={} strategy={} symbol={}: {}",
          req.getTenantId(),
          req.getStrategyId(),
          req.getContractSymbol(),
          e.getMessage());
      result.setSubscriptionId("");
      result.setStatus(SubscribePremiumResult.Status.FAILED);
      result.setError(e.getMessage());
      return result;
    }
  }

  private void dispatchTick(String posWfId, String subscriptionId, PremiumTick tick) {
    try {
      WorkflowStub stub = workflowClient.newUntypedWorkflowStub(posWfId);
      stub.signal("chandelierTick", tick);
    } catch (WorkflowNotFoundException notFound) {
      // Target workflow has closed; tear down the subscription so we stop fanning out.
      if (subscriptionId != null) {
        source.unsubscribe(subscriptionId);
      }
    } catch (Exception ignored) {
      // Best-effort tick dispatch — Phase 4 deliberately does not surface transient errors.
    }
  }
}
