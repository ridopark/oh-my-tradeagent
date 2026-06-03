package com.ohmytradeagent.exec.alert;

import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.identity.YahooOptionLink;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Issue #297 / #295: best-effort Discord alert for a broker rejection on {@code
 * ExecActivitiesImpl.placeOrder}. This is the failure that caused the #295 outage (a 422 from the
 * broker left the order intent stuck RECORDED) and which went unnoticed for ~40 minutes — broker
 * rejections are recorded in the exec {@code order_intent} journal {@code last_error}, NOT the
 * orchestrator {@code audit_log}, so this alert is dispatched at the exec failure seam directly.
 *
 * <p>The action is derived from the order side: {@code BUY} = BTO (entry), {@code SELL} = STC
 * (exit). The message carries the symbol, the broker reason, and enough identifiers ({@code
 * intent_key}, {@code client_order_id}) to find the failure — the orchestrator workflow id is not
 * in scope at this seam.
 *
 * <p>NON-BLOCKING GUARANTEE: {@link #onBrokerRejection} never throws and never blocks the caller.
 * Issue #302: the (potentially slow ~5s) webhook {@code post} is handed to a bounded async executor
 * so the timeout is NOT consumed inline on the broker-rejection path before the original broker
 * exception is rethrown. The webhook client is itself best-effort, but as belt-and-suspenders the
 * dispatch is wrapped so any unexpected error on the dispatch thread is caught and logged rather
 * than propagated. Critically, this must not alter the original broker exception's Temporal
 * retryable/non-retryable classification — the alert is enqueued alongside {@code
 * journal.markPlaceFailed}, and the original exception is rethrown unchanged by the caller.
 *
 * <p>If the bounded executor's queue is saturated (a burst of rejections while the webhook is slow)
 * the rejected submission is swallowed-and-logged — dropping a notification is always preferable to
 * blocking or failing the order path.
 *
 * <p>Toggleable via {@code alert.discord.broker-rejection.enabled} (default {@code true}) for
 * single-knob disable without a code change.
 */
@Component
public class BrokerRejectionAlerter {

  private static final Logger log = LoggerFactory.getLogger(BrokerRejectionAlerter.class);

  private final WebhookClient webhookClient;
  private final boolean enabled;
  private final Executor dispatchExecutor;

  @org.springframework.beans.factory.annotation.Autowired
  public BrokerRejectionAlerter(
      WebhookClient webhookClient,
      @Value("${alert.discord.broker-rejection.enabled:true}") boolean enabled) {
    this(webhookClient, enabled, defaultDispatchExecutor());
  }

  /**
   * Explicit-executor seam (also used by tests). Tests pass a synchronous ({@code Runnable::run})
   * executor so the {@code webhookClient.post} interaction is deterministically observable; the
   * production {@code @Autowired} constructor uses {@link #defaultDispatchExecutor()} (a bounded
   * single daemon thread).
   */
  public BrokerRejectionAlerter(
      WebhookClient webhookClient, boolean enabled, Executor dispatchExecutor) {
    this.webhookClient = webhookClient;
    this.enabled = enabled;
    this.dispatchExecutor = dispatchExecutor;
  }

  /**
   * Called from the {@code placeOrder} catch block alongside {@code journal.markPlaceFailed}. Best
   * effort and non-blocking: never throws, never blocks, never changes the surrounding exception
   * flow. The webhook {@code post} runs on the async dispatch executor (issue #302) so the ~5s
   * timeout is not consumed inline before the broker exception is rethrown unchanged.
   *
   * @param intent the order intent that the broker rejected
   * @param clientOrderId the bounded broker-facing client_order_id derived from the intent_key
   * @param reason the broker rejection message ({@code e.getMessage()})
   */
  public void onBrokerRejection(OrderIntent intent, String clientOrderId, String reason) {
    if (!enabled) {
      return;
    }
    // Build the message on the caller thread (cheap, deterministic) but POST asynchronously so the
    // webhook timeout never blocks the broker/order path. A failure to even enqueue (queue full /
    // executor rejected) is swallowed — dropping a notification beats blocking the order path.
    final WebhookEmbed embed;
    try {
      embed = buildEmbed(intent, clientOrderId, reason);
    } catch (RuntimeException e) {
      log.warn("broker-rejection-alert build failed intent_key={}", safeIntentKey(intent), e);
      return;
    }
    try {
      dispatchExecutor.execute(
          () -> {
            try {
              webhookClient.postEmbed(embed);
            } catch (RuntimeException e) {
              // Defensive: an error on the dispatch thread must never surface — the order path has
              // already moved on and is rethrowing the original broker exception.
              log.warn(
                  "broker-rejection-alert async dispatch failed intent_key={}",
                  safeIntentKey(intent),
                  e);
            }
          });
    } catch (RuntimeException e) {
      // Executor rejected the task (e.g. bounded queue saturated). Never propagate.
      log.warn(
          "broker-rejection-alert could not enqueue dispatch intent_key={}",
          safeIntentKey(intent),
          e);
    }
  }

  /**
   * Bounded single-daemon-thread dispatch executor: one worker, a small bounded queue, and a
   * caller-rejects (abort) saturation policy so a slow/hung webhook can at worst drop notifications
   * — it can never grow an unbounded backlog or block the broker/order path. Daemon-threaded so it
   * does not keep the JVM alive on shutdown.
   */
  private static Executor defaultDispatchExecutor() {
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),
            runnable -> {
              Thread thread = new Thread(runnable, "broker-rejection-alert-dispatch");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }

  /** Discord red (0xED4245) as the decimal RGB integer — failure/rejection accent. */
  private static final int DISCORD_RED = 15548997;

  /**
   * Builds the red broker-rejection embed: title carries the action, the contract symbol field is a
   * Yahoo-linked OCC (plain text on a malformed symbol — never throws), and the operator-actionable
   * ids ({@code intent_key}, {@code client_order_id}) are stacked fields. The low-signal
   * tenant/strategy trace is demoted to the footer.
   */
  private static WebhookEmbed buildEmbed(OrderIntent intent, String clientOrderId, String reason) {
    String action = actionFor(intent);
    String title = ":rotating_light: Copytrade order FAILED — " + action + " (broker rejection)";
    String symbol = intent == null ? null : intent.getOptionSymbol();

    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("symbol", YahooOptionLink.markdown(symbol), false));
    fields.add(new WebhookEmbed.Field("reason", orNa(reason), false));
    fields.add(new WebhookEmbed.Field("intent_key", safeIntentKey(intent), false));
    fields.add(new WebhookEmbed.Field("client_order_id", orNa(clientOrderId), false));

    return new WebhookEmbed(title, DISCORD_RED, footerFor(intent), fields);
  }

  /** Low-signal trace ids demoted to the footer (tenant/strategy). */
  private static String footerFor(OrderIntent intent) {
    String tenant = intent == null ? null : intent.getTenantId();
    String strategy = intent == null ? null : intent.getStrategyId();
    return "tenant/strategy: " + orNa(tenant) + "/" + orNa(strategy);
  }

  private static String actionFor(OrderIntent intent) {
    if (intent == null || intent.getSide() == null) {
      return "BTO/STC";
    }
    return intent.getSide() == OrderIntent.Side.SELL ? "STC (exit)" : "BTO (entry)";
  }

  private static String safeIntentKey(OrderIntent intent) {
    return intent == null ? "n/a" : orNa(intent.getIntentKey());
  }

  private static String orNa(String value) {
    return value == null || value.isBlank() ? "n/a" : value;
  }
}
