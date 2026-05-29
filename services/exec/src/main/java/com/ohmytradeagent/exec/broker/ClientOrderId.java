package com.ohmytradeagent.exec.broker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Issue #295: derives the broker-facing {@code client_order_id} from an internal {@code
 * intent_key}, bounded to Alpaca's 128-char limit.
 *
 * <p>The internal {@code intent_key} (journal PK; also encodes the {@code :exit:} STC routing
 * marker parsed by {@code FillDispatcherImpl.resolveTargetWorkflowId}) is left untouched. An exit
 * intent_key on a real OCC symbol plus two Discord snowflakes is 161 chars — over the 128-char
 * Alpaca cap — so passing it straight through made Alpaca reject every STC SELL with a
 * non-retryable 422, stranding positions open. This bounds only the wire value.
 *
 * <p>Scheme: a readable, charset-safe prefix taken from the head of the {@code intent_key} (so an
 * operator can still recognise the order at a glance), an {@code '_'} separator, then the full
 * SHA-256 hex digest of the <em>complete, untouched</em> {@code intent_key}. Properties:
 *
 * <ul>
 *   <li><b>Deterministic / idempotent</b>: a pure function of the full {@code intent_key}, so
 *       Temporal retries, orchestrator-svc restarts, and concurrent task-queue workers all submit
 *       the identical {@code client_order_id} and Alpaca dedupes on it.
 *   <li><b>Collision-safe</b>: the digest covers the entire {@code intent_key}, so entry / exit /
 *       flatten / retry of the same position — whose {@code intent_key}s differ only in the suffix
 *       — always yield distinct ids; no entry/exit collision.
 *   <li><b>Bounded</b>: prefix (≤ {@value #PREFIX_MAX}) + {@code '_'} + 64-char hex digest is at
 *       most {@value #PREFIX_MAX} + 1 + 64 = 89 chars, comfortably under 128.
 * </ul>
 */
public final class ClientOrderId {

  /** Alpaca's hard cap on {@code client_order_id} length. */
  public static final int MAX_LENGTH = 128;

  /** Max chars of the readable prefix kept from the head of the intent_key. */
  private static final int PREFIX_MAX = 24;

  private ClientOrderId() {}

  /**
   * Returns the bounded, deterministic broker-facing {@code client_order_id} for {@code intentKey}.
   *
   * @throws IllegalArgumentException if {@code intentKey} is null or blank
   */
  public static String forIntent(String intentKey) {
    if (intentKey == null || intentKey.isBlank()) {
      throw new IllegalArgumentException("intentKey is required");
    }
    String prefix = sanitizedPrefix(intentKey);
    String digest = sha256Hex(intentKey);
    String id = prefix.isEmpty() ? digest : prefix + "_" + digest;
    // Defensive: prefix is already capped at PREFIX_MAX so this never truncates in practice, but it
    // guarantees the contract even if PREFIX_MAX is later raised.
    return id.length() <= MAX_LENGTH ? id : id.substring(0, MAX_LENGTH);
  }

  /**
   * Keep the head of the intent_key as a human hint, mapping anything outside {@code
   * [A-Za-z0-9._-]} (e.g. the OCC embedded spaces and the {@code /} / {@code :} separators) to
   * {@code '-'} so the wire value stays within a safe broker charset and carries no whitespace.
   */
  private static String sanitizedPrefix(String intentKey) {
    int len = Math.min(intentKey.length(), PREFIX_MAX);
    StringBuilder sb = new StringBuilder(len);
    for (int i = 0; i < len; i++) {
      char c = intentKey.charAt(i);
      boolean safe =
          (c >= 'A' && c <= 'Z')
              || (c >= 'a' && c <= 'z')
              || (c >= '0' && c <= '9')
              || c == '.'
              || c == '_'
              || c == '-';
      sb.append(safe ? c : '-');
    }
    return sb.toString();
  }

  private static String sha256Hex(String value) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandated by the JLS for every JVM; absence is unrecoverable.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
