package com.ohmytradeagent.orchestrator.alert;

/**
 * Minimal seam for posting a plain-text message to a webhook (Discord). Extracted as an interface
 * so the alerter can be unit-tested against a mock without an HTTP server, and so the transport
 * (JDK {@link java.net.http.HttpClient}) is swappable.
 *
 * <p>Issue #297: every implementation MUST be best-effort and non-blocking from the caller's
 * perspective — a transport failure (timeout, non-2xx, exception) is the implementation's problem
 * to log, never the caller's to propagate. The trading path must not be able to fail because a
 * notification webhook is down (the #295 outage class).
 */
public interface WebhookClient {

  /**
   * Post {@code content} to the configured webhook. Implementations must NOT throw on transport
   * failure — they swallow-and-log so the calling trading/audit path is never disrupted.
   *
   * @param content the message body to deliver
   */
  void post(String content);

  /**
   * Post a rich {@link WebhookEmbed} to the configured webhook. Same best-effort contract as {@link
   * #post(String)}: blank URL is a no-op and transport failures are swallowed-and-logged, never
   * propagated to the caller.
   *
   * <p>Default is a no-op so that text-only alerters (which never build embeds) need not implement
   * it and {@link WebhookClient} stays usable as a single-method lambda at those call sites. The
   * real Discord transport overrides this.
   *
   * @param embed the embed to deliver
   */
  default void postEmbed(WebhookEmbed embed) {
    // No-op by default; the Discord transport overrides this.
  }
}
