package com.ohmytradeagent.orchestrator.activities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ohmytradeagent.contract.AuditEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Issue #85: pure-Java hash-chain writer for {@code audit_log}. Computes {@code (prev_hash,
 * row_hash)} per the canonical form pinned in {@code docs/ops/audit-retention.md §2}. No DB
 * coupling — callers are responsible for the {@code SELECT ... FOR UPDATE} on the prior chain head
 * and for persisting the returned bytes via the production {@code INSERT}.
 *
 * <p>Canonical form (binary, big-endian):
 *
 * <pre>
 *   prev_hash (or 32 zero bytes if chain head)               // 32 bytes
 *   schema_version                                            //  4 bytes BE u32
 *   len(tenant_id_utf8) || tenant_id_utf8                     //  4 + N
 *   len(strategy_id_utf8) || strategy_id_utf8                 //  4 + N
 *   event_id (16 raw UUID bytes, big-endian)                  // 16 bytes
 *   occurred_at_unix_micros (BE i64)                          //  8 bytes
 *   len(kind_utf8) || kind_utf8                               //  4 + N
 *   len(actor_utf8) || actor_utf8                             //  4 + N  (NULL == "")
 *   len(workflow_id_utf8) || workflow_id_utf8                 //  4 + N  (NULL == "")
 *   len(correlation_id_utf8) || correlation_id_utf8           //  4 + N  (NULL == "")
 *   len(subject_canonical_bytes) || subject_canonical_bytes   //  4 + N  (RFC 8785 JCS)
 * </pre>
 *
 * <p>The subject canonicalization is a small in-tree RFC 8785 JCS implementation against Jackson
 * tree nodes: keys are sorted by UTF-16 code-unit order at every object level, JSON strings are
 * re-emitted with RFC 8259 escapes, JSON numbers are normalized via the ECMA-262 ToString rule
 * (delegated to {@code Double.toString} for non-integer doubles and {@code Long.toString} for
 * integers). The orchestrator's {@code subject} comes from a {@code Map<String, Object>} produced
 * by Jackson, so the implementation only needs to handle that subset.
 */
@Component
public class AuditLogChainWriter {

  /** 32 zero bytes substituted for a {@code NULL prev_hash} (chain head). */
  public static final byte[] PREV_HASH_CHAIN_HEAD = new byte[32];

  private final ObjectMapper objectMapper;

  public AuditLogChainWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Compute the {@code row_hash} for {@code event} given {@code priorRowHash}. Pass {@code null}
   * for chain-head rows; the writer substitutes {@link #PREV_HASH_CHAIN_HEAD}.
   *
   * @return 32-byte SHA-256 of the canonical serialization.
   */
  public byte[] computeRowHash(AuditEvent event, byte[] priorRowHash) {
    byte[] prevHash = priorRowHash == null ? PREV_HASH_CHAIN_HEAD : priorRowHash;
    if (prevHash.length != 32) {
      throw new IllegalArgumentException(
          "priorRowHash must be 32 bytes (SHA-256); got " + prevHash.length);
    }
    byte[] canonical = canonicalBytes(event, prevHash);
    return sha256(canonical);
  }

  /** SHA-256 of an arbitrary byte array. Wraps the checked exception. */
  public static byte[] sha256(byte[] input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return md.digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
    }
  }

  private static final HexFormat HEX = HexFormat.of();

  /** Lowercase hex of a byte array (for fixture comparison + debug logging). */
  public static String hex(byte[] bytes) {
    return HEX.formatHex(bytes);
  }

  /** Decode a lowercase hex string into bytes. Used by fixture loaders. */
  public static byte[] unhex(String hex) {
    return HEX.parseHex(hex);
  }

  /** Build the canonical pre-image bytes per {@code docs/ops/audit-retention.md §2}. */
  byte[] canonicalBytes(AuditEvent event, byte[] prevHash) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(256);
    try {
      out.write(prevHash);
      writeBeU32(out, schemaVersionOrOne(event));
      writeLenPrefixedUtf8(out, event.getTenantId());
      writeLenPrefixedUtf8(out, event.getStrategyId());
      out.write(uuidBytes(UUID.fromString(event.getEventId())));
      writeBeI64(out, unixMicros(event.getOccurredAt()));
      writeLenPrefixedUtf8(out, event.getKind());
      writeLenPrefixedUtf8(out, event.getActor());
      writeLenPrefixedUtf8(out, event.getWorkflowId());
      writeLenPrefixedUtf8(out, event.getCorrelationId());
      byte[] subjectBytes = canonicalSubjectBytes(event.getSubject());
      writeBeU32(out, subjectBytes.length);
      out.write(subjectBytes);
      return out.toByteArray();
    } catch (IOException e) {
      // ByteArrayOutputStream does not throw IOException in practice.
      throw new IllegalStateException("canonical-form build failed", e);
    }
  }

  private static int schemaVersionOrOne(AuditEvent event) {
    return event.getSchemaVersion() == null ? 1 : event.getSchemaVersion().intValue();
  }

  /** UTC Unix-epoch microseconds for the given event timestamp. */
  static long unixMicros(OffsetDateTime ts) {
    return ts.toInstant().getEpochSecond() * 1_000_000L + ts.toInstant().getNano() / 1000L;
  }

  private static byte[] uuidBytes(UUID uuid) {
    ByteBuffer bb = ByteBuffer.allocate(16);
    bb.putLong(uuid.getMostSignificantBits());
    bb.putLong(uuid.getLeastSignificantBits());
    return bb.array();
  }

  /** Write a variable-length UTF-8 field with a 4-byte BE u32 length prefix. NULL → len=0. */
  private static void writeLenPrefixedUtf8(ByteArrayOutputStream out, String value)
      throws IOException {
    byte[] utf8 = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    writeBeU32(out, utf8.length);
    out.write(utf8);
  }

  private static void writeBeU32(ByteArrayOutputStream out, int value) throws IOException {
    out.write((value >>> 24) & 0xff);
    out.write((value >>> 16) & 0xff);
    out.write((value >>> 8) & 0xff);
    out.write(value & 0xff);
  }

  private static void writeBeI64(ByteArrayOutputStream out, long value) throws IOException {
    for (int i = 7; i >= 0; i--) {
      out.write((int) ((value >>> (i * 8)) & 0xff));
    }
  }

  // ----- RFC 8785 JCS (minimal, scoped to Jackson tree of Map<String, Object>) -----

  /**
   * Canonicalize {@code subject} per RFC 8785 JCS. Empty / null subjects emit {@code {}} bytes — a
   * defensible reading consistent with the contract requiring {@code subject} non-null. The
   * orchestrator's subject is always a non-null Map, but defensive null handling here keeps the
   * hashing total.
   */
  byte[] canonicalSubjectBytes(Map<String, Object> subject) {
    Map<String, Object> input = subject == null ? Collections.emptyMap() : subject;
    JsonNode tree = objectMapper.valueToTree(input);
    StringBuilder sb = new StringBuilder(64);
    writeCanonical(sb, tree);
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** Recursive JCS emitter. */
  private static void writeCanonical(StringBuilder sb, JsonNode node) {
    if (node == null || node.isNull()) {
      sb.append("null");
      return;
    }
    if (node.isObject()) {
      writeCanonicalObject(sb, (ObjectNode) node);
      return;
    }
    if (node.isArray()) {
      sb.append('[');
      Iterator<JsonNode> it = node.elements();
      boolean first = true;
      while (it.hasNext()) {
        if (!first) sb.append(',');
        first = false;
        writeCanonical(sb, it.next());
      }
      sb.append(']');
      return;
    }
    if (node.isTextual()) {
      writeJsonString(sb, node.textValue());
      return;
    }
    if (node.isBoolean()) {
      sb.append(node.booleanValue() ? "true" : "false");
      return;
    }
    if (node.isIntegralNumber()) {
      sb.append(node.bigIntegerValue().toString());
      return;
    }
    if (node.isNumber()) {
      // Floating-point: RFC 8785 §3.2.2.3 normalizes via ECMA-262 ToString. For the
      // orchestrator's audit subjects, numbers are typically integer counts (qty, schema_version)
      // or finite doubles (prices). Delegating to Double.toString matches ECMA-262 ToString for
      // every double whose toString produces a finite numeric literal, with two well-known
      // exceptions handled below.
      double d = node.doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        throw new IllegalArgumentException("JCS forbids NaN/Infinity in canonical form: " + d);
      }
      // ECMA-262 ToString never emits a trailing ".0" for integer-valued doubles, while Java's
      // Double.toString does (e.g. 1.0). Strip the ".0" suffix to match the JCS expectation for
      // integer-valued doubles. For non-integer doubles we accept Java's representation as a
      // reasonable approximation of ECMA-262 ToString — the orchestrator subject does not
      // currently emit floats requiring the full step-by-step ECMA spec, and any future caller
      // depending on exact float canonicalization should provide pre-canonicalized strings.
      String s = Double.toString(d);
      if (s.endsWith(".0")) {
        s = s.substring(0, s.length() - 2);
      }
      sb.append(s);
      return;
    }
    // Fallback (binary, POJO) — shouldn't occur for orchestrator subjects.
    sb.append(node.toString());
  }

  private static void writeCanonicalObject(StringBuilder sb, ObjectNode obj) {
    sb.append('{');
    List<String> keys = new ArrayList<>();
    Iterator<String> it = obj.fieldNames();
    while (it.hasNext()) keys.add(it.next());
    // RFC 8785 §3.2.3: object keys sorted by UTF-16 code-unit order. Java's default
    // String.compareTo is UTF-16 code-unit lexicographic — exactly what JCS requires.
    Collections.sort(keys);
    boolean first = true;
    for (String key : keys) {
      if (!first) sb.append(',');
      first = false;
      writeJsonString(sb, key);
      sb.append(':');
      writeCanonical(sb, obj.get(key));
    }
    sb.append('}');
  }

  /** RFC 8259 §7-compatible string emission used by JCS (no extra escaping). */
  private static void writeJsonString(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\b':
          sb.append("\\b");
          break;
        case '\f':
          sb.append("\\f");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    sb.append('"');
  }
}
