package com.ohmytradeagent.marketdata.activities;

import com.ohmytradeagent.contract.PremiumTick;
import com.ohmytradeagent.contract.SubscribePremiumRequest;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.contract.activities.SubscribePremiumActivity;
import com.ohmytradeagent.marketdata.provider.MarketDataProvider;
import com.ohmytradeagent.marketdata.provider.Subscription;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 4 / Phase 2c.2 worker-side implementation of {@link SubscribePremiumActivity}. Subscribes a
 * tick consumer on the Spring-wired {@link MarketDataProvider} (in-memory by default; Alpaca when
 * {@code MARKET_DATA_PROVIDER=alpaca}) and signals each tick into the named PositionWorkflow as
 * {@code chandelierTick}.
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

  private final MarketDataProvider provider;
  private final WorkflowClient workflowClient;
  private final ExecutorService dispatcher;

  /**
   * Subscription registry keyed by subscription_id. Phase 4 had no explicit unsubscribe path beyond
   * the workflow-not-found self-tear-down; we keep that pattern here.
   */
  private final ConcurrentHashMap<String, Subscription> active = new ConcurrentHashMap<>();

  public SubscribePremiumActivityImpl(
      MarketDataProvider provider, WorkflowClient workflowClient, ExecutorService dispatcher) {
    this.provider = provider;
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
      final String[] subIdHolder = new String[1];
      Subscription sub =
          provider.subscribePremium(
              req.getContractSymbol(),
              tick ->
                  dispatcher.submit(
                      () -> dispatchTick(posWfId, subIdHolder[0], toPremiumTick(tick))));
      subIdHolder[0] = sub.subscriptionId();
      active.put(sub.subscriptionId(), sub);
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
        Subscription sub = active.remove(subscriptionId);
        if (sub != null) {
          sub.close();
        }
      }
    } catch (Exception ignored) {
      // Best-effort tick dispatch — Phase 4 deliberately does not surface transient errors.
    }
  }

  private static PremiumTick toPremiumTick(com.ohmytradeagent.marketdata.provider.Tick t) {
    PremiumTick out = new PremiumTick();
    out.setSchemaVersion(1L);
    out.setContractSymbol(t.occSymbol());
    out.setPremium(t.premium());
    out.setBid(t.bid());
    out.setAsk(t.ask());
    out.setRetrievedAt(t.retrievedAt());
    return out;
  }
}
