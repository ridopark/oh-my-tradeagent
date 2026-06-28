package com.ohmytradeagent.exec.fill;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
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
 *       Used by the single pod-wide socket (the {@code perTenantEnabled=false} default); the
 *       per-tenant path resolves each socket's URL from that tenant's credentials instead.
 *   <li>{@code perTenantEnabled} — Phase G dark flag (default FALSE). FALSE keeps exactly today's
 *       single pod-wide socket on {@code wsUrl} using the pod-wide env creds. TRUE opens one
 *       independently-supervised, per-tenant-authenticated socket per enumerated live tenant.
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
    boolean enabled,
    String wsUrl,
    boolean perTenantEnabled,
    long reconnectBaseMs,
    long reconnectCapMs,
    int dedupCacheSize) {

  private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

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
    // The pod-wide wsUrl backs only the single-socket (perTenantEnabled=false) path; the per-tenant
    // path resolves + validates each socket's URL from that tenant's creds at connect time, so a
    // per-tenant deployment need not also set a (then-unused) pod-wide wsUrl.
    if (enabled && !perTenantEnabled) {
      validateWsUrl(wsUrl);
    }
  }

  /**
   * Validates a trade-updates WS URL: {@code wss://} for any host, plaintext {@code ws://} only for
   * a loopback host (the in-process test fixture). The handshake's first frame carries the broker
   * key + secret, so a non-loopback {@code ws://} would leak credentials in the clear. Shared by
   * the record's compact-constructor check (pod-wide URL) and the per-tenant path's per-socket
   * resolved-URL check.
   */
  public static void requireSecureWsUrl(String wsUrl) {
    validateWsUrl(wsUrl);
  }

  /**
   * Reject plaintext {@code ws://} for non-loopback hosts. The handshake carries the broker API key
   * + secret in the first frame; a misconfigured {@code EXEC_FILL_LISTENER_WS_URL=ws://prod...}
   * would send those credentials in the clear. Only loopback {@code ws://} is allowed so the
   * in-process test fixture (Java-WebSocket server on localhost) keeps working.
   */
  private static void validateWsUrl(String wsUrl) {
    if (wsUrl == null || wsUrl.isBlank()) {
      throw new IllegalArgumentException("exec.fill-listener.ws-url is required when enabled=true");
    }
    URI uri;
    try {
      uri = new URI(wsUrl);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(
          "exec.fill-listener.ws-url is not a valid URI: " + wsUrl, e);
    }
    String scheme = uri.getScheme();
    if ("wss".equalsIgnoreCase(scheme)) {
      return;
    }
    if ("ws".equalsIgnoreCase(scheme)
        && uri.getHost() != null
        && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase())) {
      return;
    }
    throw new IllegalArgumentException(
        "exec.fill-listener.ws-url must use wss:// (or ws:// for a loopback host); got " + wsUrl);
  }
}
