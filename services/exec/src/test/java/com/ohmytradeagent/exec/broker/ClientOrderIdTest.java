package com.ohmytradeagent.exec.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Issue #295: the broker-facing {@code client_order_id} must be bounded to Alpaca's 128-char limit.
 * Today the orchestrator passes the full {@code intent_key} straight through as the {@code
 * client_order_id}; an exit (STC) intent_key on a real OCC symbol plus two Discord snowflakes is
 * 161 chars, so Alpaca returns 422 (non-retryable) and every STC SELL fails — positions stuck open.
 *
 * <p>{@link ClientOrderId#forIntent(String)} derives a compact, ≤128-char id that is a PURE
 * deterministic function of the full {@code intent_key} (so Temporal retries / orchestrator
 * restarts / concurrent task-queue workers all submit the identical id and Alpaca dedupes), and
 * distinct for intents whose {@code intent_key}s differ (entry vs exit vs flatten vs retry of the
 * same position).
 */
class ClientOrderIdTest {

  // The exact 161-char exit intent_key from issue #295 (real OCC + two Discord snowflakes).
  private static final String EXIT_INTENT_KEY =
      "t-dev/s-copytrade-v1/pos/TSLA  260529C00435000/"
          + "chat-messages-769797179992571914-1509927843260268616:0"
          + ":exit:chat-messages-769797179992571914-1509928607168860170:0";

  private static final String ENTRY_INTENT_KEY =
      "t-dev/s-copytrade-v1/pos/TSLA  260529C00435000/"
          + "chat-messages-769797179992571914-1509927843260268616:0:entry";

  @Test
  void exitIntentKeyIs161CharsToday() {
    // Sanity-pin the regression case the issue documents: the raw exit intent_key exceeds 128.
    assertThat(EXIT_INTENT_KEY.length()).isEqualTo(161);
    assertThat(EXIT_INTENT_KEY.length()).isGreaterThan(128);
  }

  @Test
  void forIntent_boundsExitToAtMost128Chars() {
    String cid = ClientOrderId.forIntent(EXIT_INTENT_KEY);
    assertThat(cid.length()).isLessThanOrEqualTo(128);
  }

  @Test
  void forIntent_boundsEntryToAtMost128Chars() {
    String cid = ClientOrderId.forIntent(ENTRY_INTENT_KEY);
    assertThat(cid.length()).isLessThanOrEqualTo(128);
  }

  @Test
  void forIntent_isDeterministic_sameInputSameOutput() {
    assertThat(ClientOrderId.forIntent(EXIT_INTENT_KEY))
        .isEqualTo(ClientOrderId.forIntent(EXIT_INTENT_KEY));
  }

  @Test
  void forIntent_isCollisionSafe_entryVsExitOfSamePositionDiffer() {
    // entry and exit of the SAME position have different intent_keys → different bounded ids,
    // so Alpaca never dedupes an exit against its own entry.
    assertThat(ClientOrderId.forIntent(ENTRY_INTENT_KEY))
        .isNotEqualTo(ClientOrderId.forIntent(EXIT_INTENT_KEY));
  }

  @Test
  void forIntent_shortKeyIsBounded_andStillDeterministic() {
    // A short intent_key (already < 128) must still produce a ≤128 deterministic id.
    String shortKey = "t-dev/s-copytrade-v1/sig/sig-bto-1:entry";
    String cid = ClientOrderId.forIntent(shortKey);
    assertThat(cid.length()).isLessThanOrEqualTo(128);
    assertThat(cid).isEqualTo(ClientOrderId.forIntent(shortKey));
  }

  @Test
  void forIntent_isWithinAlpacaCharset_noWhitespace() {
    // OCC symbols carry embedded spaces (e.g. "TSLA  260529C00435000"); the bounded id must not
    // leak those into the wire value. Assert the result has no whitespace.
    String cid = ClientOrderId.forIntent(EXIT_INTENT_KEY);
    assertThat(cid).doesNotContainAnyWhitespaces();
  }

  @Test
  void forIntent_nullIntentKey_throws() {
    assertThatThrownBy(() -> ClientOrderId.forIntent(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
