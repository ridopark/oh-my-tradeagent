package com.ohmytradeagent.exec.fill;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the fill listener. Flat schema so YAML / env-var overrides per deployment stay
 * readable.
 *
 * <ul>
 *   <li>{@code enabled} — gate the entire listener (the WS bean is {@link
 *       org.springframework.boot.autoconfigure.condition.ConditionalOnProperty}-d on this).
 *   <li>{@code wsUrl} — Alpaca trade-updates endpoint (paper: {@code
 *       wss://paper-api.alpaca.markets/stream}, live: {@code wss://api.alpaca.markets/stream}).
 *   <li>{@code reconnectBaseMs} / {@code reconnectCapMs} — exponential-backoff bounds.
 *   <li>{@code dedupCacheSize} — bounded LRU keyed on {@code (broker_order_id, filled_qty)} so WS
 *       reconnect-replays don't double-dispatch.
 * </ul>
 *
 * <p>The compact constructor fails loud on non-positive numeric values: a {@code 0} or negative
 * timeout almost always means a typo or a miswired env var, and silently substituting a default
 * would mask the problem until a 3 a.m. log.
 */
@ConfigurationProperties(prefix = "exec.fill-listener")
public record FillListenerProperties(
    boolean enabled, String wsUrl, long reconnectBaseMs, long reconnectCapMs, int dedupCacheSize) {

  public FillListenerProperties {
    if (reconnectBaseMs <= 0L) {
      throw new IllegalArgumentException(
          "exec.fill-listener.reconnect-base-ms must be > 0, got " + reconnectBaseMs);
    }
    if (reconnectCapMs <= 0L) {
      throw new IllegalArgumentException(
          "exec.fill-listener.reconnect-cap-ms must be > 0, got " + reconnectCapMs);
    }
    if (reconnectCapMs < reconnectBaseMs) {
      throw new IllegalArgumentException(
          "exec.fill-listener.reconnect-cap-ms ("
              + reconnectCapMs
              + ") must be >= reconnect-base-ms ("
              + reconnectBaseMs
              + ")");
    }
    if (dedupCacheSize <= 0) {
      throw new IllegalArgumentException(
          "exec.fill-listener.dedup-cache-size must be > 0, got " + dedupCacheSize);
    }
  }
}
