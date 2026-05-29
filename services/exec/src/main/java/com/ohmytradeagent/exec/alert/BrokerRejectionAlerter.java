package com.ohmytradeagent.exec.alert;

import com.ohmytradeagent.contract.OrderIntent;
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
 * <p>NON-BLOCKING GUARANTEE: {@link #onBrokerRejection} never throws. The webhook client is itself
 * best-effort, but as belt-and-suspenders the dispatch is wrapped so any unexpected error is caught
 * and logged rather than propagated. Critically, this must not alter the original broker
 * exception's Temporal retryable/non-retryable classification — the alert fires alongside {@code
 * journal.markPlaceFailed}, before the original exception is rethrown unchanged.
 *
 * <p>Toggleable via {@code alert.discord.broker-rejection.enabled} (default {@code true}) for
 * single-knob disable without a code change.
 */
@Component
public class BrokerRejectionAlerter {

  private static final Logger log = LoggerFactory.getLogger(BrokerRejectionAlerter.class);

  private final WebhookClient webhookClient;
  private final boolean enabled;

  public BrokerRejectionAlerter(
      WebhookClient webhookClient,
      @Value("${alert.discord.broker-rejection.enabled:true}") boolean enabled) {
    this.webhookClient = webhookClient;
    this.enabled = enabled;
  }

  /**
   * Called from the {@code placeOrder} catch block alongside {@code journal.markPlaceFailed}. Best
   * effort and non-blocking: never throws, never changes the surrounding exception flow.
   *
   * @param intent the order intent that the broker rejected
   * @param clientOrderId the bounded broker-facing client_order_id derived from the intent_key
   * @param reason the broker rejection message ({@code e.getMessage()})
   */
  public void onBrokerRejection(OrderIntent intent, String clientOrderId, String reason) {
    if (!enabled) {
      return;
    }
    try {
      webhookClient.post(buildMessage(intent, clientOrderId, reason));
    } catch (RuntimeException e) {
      // Defensive: a notification must never break the broker/order path or mask the broker
      // rejection that is about to be rethrown.
      log.warn(
          "broker-rejection-alert build/dispatch failed intent_key={}", safeIntentKey(intent), e);
    }
  }

  private static String buildMessage(OrderIntent intent, String clientOrderId, String reason) {
    String action = actionFor(intent);
    String symbol = intent == null ? "n/a" : orNa(intent.getOptionSymbol());
    StringBuilder sb = new StringBuilder();
    sb.append(":rotating_light: Copytrade order FAILED — ")
        .append(action)
        .append(" (broker rejection)")
        .append('\n')
        .append("symbol: ")
        .append(symbol)
        .append('\n')
        .append("reason: ")
        .append(orNa(reason))
        .append('\n')
        .append("intent_key: ")
        .append(safeIntentKey(intent))
        .append('\n')
        .append("client_order_id: ")
        .append(orNa(clientOrderId));
    return sb.toString();
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
