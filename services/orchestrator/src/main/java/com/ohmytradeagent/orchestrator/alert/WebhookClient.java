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
   * Tenant-scoped overload of {@link #post(String)}: deliver {@code content} to the webhook
   * configured for {@code tenantId}, falling back to the global default when that tenant has no
   * dedicated webhook. Same best-effort contract: never throws.
   *
   * <p>Default delegates to the no-arg {@link #post(String)} (i.e. always the global default) so
   * that mocks/lambdas keep compiling and only the real Discord transport implements per-tenant
   * routing. A {@code null}/blank {@code tenantId} resolves to the global default.
   *
   * @param tenantId the tenant whose webhook should receive the message (null/blank → global)
   * @param content the message body to deliver
   */
  default void post(String tenantId, String content) {
    post(content);
  }

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

  /**
   * Tenant-scoped overload of {@link #postEmbed(WebhookEmbed)}: deliver {@code embed} to the
   * webhook configured for {@code tenantId}, falling back to the global default when that tenant
   * has no dedicated webhook. Same best-effort contract: never throws.
   *
   * <p>Default delegates to the no-arg {@link #postEmbed(WebhookEmbed)} (i.e. always the global
   * default) so that mocks/lambdas keep compiling and only the real Discord transport implements
   * per-tenant routing. A {@code null}/blank {@code tenantId} resolves to the global default.
   *
   * @param tenantId the tenant whose webhook should receive the embed (null/blank → global)
   * @param embed the embed to deliver
   */
  default void postEmbed(String tenantId, WebhookEmbed embed) {
    postEmbed(embed);
  }
}
