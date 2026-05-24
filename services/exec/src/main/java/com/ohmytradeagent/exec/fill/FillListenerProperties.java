package com.ohmytradeagent.exec.fill;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the fill listener. Keep the schema flat for easy YAML/env-var override per
 * deployment.
 *
 * <ul>
 *   <li>{@code enabled} — gate the entire listener (WS bean is {@link
 *       org.springframework.boot.autoconfigure.condition.ConditionalOnProperty}-d on this). Default
 *       {@code false} in {@code application.yml} so dev/test profiles boot without it; prod
 *       deployments set {@code EXEC_FILL_LISTENER_ENABLED=true}.
 *   <li>{@code wsUrl} — Alpaca trade-updates endpoint. Paper: {@code
 *       wss://paper-api.alpaca.markets/stream}. Live: {@code wss://api.alpaca.markets/stream}.
 *   <li>{@code reconnectBaseMs} / {@code reconnectCapMs} — exponential-backoff bounds for the
 *       reconnect loop.
 *   <li>{@code dedupCacheSize} — bounded LRU keyed on {@code (broker_order_id, filled_qty)} so WS
 *       reconnect-replays don't double-dispatch within the window.
 * </ul>
 */
@ConfigurationProperties(prefix = "exec.fill-listener")
public record FillListenerProperties(
    boolean enabled, String wsUrl, long reconnectBaseMs, long reconnectCapMs, int dedupCacheSize) {

  public FillListenerProperties {
    if (reconnectBaseMs <= 0L) {
      reconnectBaseMs = 1_000L;
    }
    if (reconnectCapMs <= 0L) {
      reconnectCapMs = 60_000L;
    }
    if (dedupCacheSize <= 0) {
      dedupCacheSize = 1024;
    }
  }
}
