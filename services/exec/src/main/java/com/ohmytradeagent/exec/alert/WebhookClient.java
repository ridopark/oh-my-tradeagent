package com.ohmytradeagent.exec.alert;

/**
 * Minimal seam for posting a plain-text message to a webhook (Discord). Extracted as an interface
 * so the alerter can be unit-tested against a mock without an HTTP server.
 *
 * <p>Issue #297: every implementation MUST be best-effort and non-blocking from the caller's
 * perspective — a transport failure (timeout, non-2xx, exception) is the implementation's problem
 * to log, never the caller's to propagate. The exec broker-rejection path (the #295 outage class)
 * must not be able to fail or change its Temporal retry classification because a notification
 * webhook is down.
 */
public interface WebhookClient {

  /**
   * Post {@code content} to the configured webhook. Implementations must NOT throw on transport
   * failure — they swallow-and-log so the calling broker/order path is never disrupted.
   *
   * @param content the message body to deliver
   */
  void post(String content);
}
