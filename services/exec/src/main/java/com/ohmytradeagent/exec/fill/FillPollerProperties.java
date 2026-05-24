package com.ohmytradeagent.exec.fill;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for {@link FillPoller}. Separated from {@link FillListenerProperties} because the poller
 * has its own enable-flag and lifecycle independent of the WebSocket transport.
 *
 * <ul>
 *   <li>{@code enabled} — gate the poller bean.
 *   <li>{@code intervalMs} — scheduler cadence (fixedDelay).
 *   <li>{@code graceMs} — rows newer than {@code now - graceMs} are skipped (the WS almost always
 *       wins inside this window; polling them would waste broker rate budget).
 *   <li>{@code batchSize} — max rows scanned per cycle (caps worst-case broker call volume).
 * </ul>
 *
 * <p>Compact constructor fails loud on invalid values for the same reason as {@link
 * FillListenerProperties} — a zero / negative timeout is almost always a typo.
 */
@ConfigurationProperties(prefix = "exec.fill-listener.poll")
public record FillPollerProperties(boolean enabled, long intervalMs, long graceMs, int batchSize) {

  public FillPollerProperties {
    if (intervalMs <= 0L) {
      throw new IllegalArgumentException(
          "exec.fill-listener.poll.interval-ms must be > 0, got " + intervalMs);
    }
    if (graceMs < 0L) {
      throw new IllegalArgumentException(
          "exec.fill-listener.poll.grace-ms must be >= 0, got " + graceMs);
    }
    if (batchSize <= 0) {
      throw new IllegalArgumentException(
          "exec.fill-listener.poll.batch-size must be > 0, got " + batchSize);
    }
  }
}
