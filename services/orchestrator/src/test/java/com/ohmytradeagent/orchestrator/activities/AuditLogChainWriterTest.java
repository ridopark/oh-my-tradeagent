package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.contract.AuditEvent;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Issue #85: pure-Java golden-vector test for {@link AuditLogChainWriter} + {@link
 * AuditMerkleRoot}. No Docker, no DB — loads {@code golden-vectors.json}, recomputes each {@code
 * row_hash} via the production writer, asserts equality against the fixture, then asserts the
 * pinned Bitcoin-style duplicate-last-on-odd Merkle root matches.
 *
 * <p>If this test ever fails, the canonical hash form in {@code docs/ops/audit-retention.md §2} has
 * drifted from the implementation. The fixture is the binding contract — either the doc / impl is
 * wrong, or the fixture must be regenerated and the on-disk audit-log chain is no longer verifiable
 * against the new form.
 */
class AuditLogChainWriterTest {

  private static ObjectMapper om;
  private static AuditLogChainWriter writer;
  private static JsonNode fixture;

  @BeforeAll
  static void loadFixture() throws Exception {
    om = new ObjectMapper().registerModule(new JavaTimeModule());
    writer = new AuditLogChainWriter(om);
    try (InputStream is =
        AuditLogChainWriterTest.class.getResourceAsStream("/audit-log/golden-vectors.json")) {
      assertThat(is).as("fixture must be on classpath").isNotNull();
      fixture = om.readTree(is);
    }
  }

  @Test
  void fixtureContainsAtLeastFourChainRows() {
    // Plan acceptance criterion 3 ([verbatim]): "At least 4 deterministic rows".
    assertThat(fixture.get("rows").size()).isGreaterThanOrEqualTo(4);
  }

  @Test
  void fixtureCoversNullActorAndEmptyStringActor() {
    // NULL ≡ empty rule: at least one row with JSON null actor and one with "" actor must both
    // be present in the fixture so the test corpus pins the equivalence.
    boolean sawNull = false;
    boolean sawEmpty = false;
    for (JsonNode row : fixture.get("rows")) {
      JsonNode actor = row.get("event").get("actor");
      if (actor == null || actor.isNull()) sawNull = true;
      else if (actor.isTextual() && actor.textValue().isEmpty()) sawEmpty = true;
    }
    assertThat(sawNull).as("fixture must include a row with NULL actor").isTrue();
    assertThat(sawEmpty).as("fixture must include a row with empty-string actor").isTrue();
  }

  @Test
  void nullActorAndEmptyActorHashIdentically() {
    // Strong pin of the NULL ≡ empty rule from docs/ops/audit-retention.md §2: build two
    // synthetic events identical except for actor (null vs ""); both must hash to the same
    // row_hash given the same prior_row_hash. This guards against an accidental future change
    // where a NULL UTF-8 field starts serializing differently from an empty one.
    AuditEvent base = buildSyntheticEvent();
    base.setActor(null);
    byte[] hashNull = writer.computeRowHash(base, null);
    base.setActor("");
    byte[] hashEmpty = writer.computeRowHash(base, null);
    assertThat(AuditLogChainWriter.hex(hashNull))
        .as("NULL actor and empty-string actor must hash identically")
        .isEqualTo(AuditLogChainWriter.hex(hashEmpty));
  }

  @Test
  void nullWorkflowIdAndEmptyWorkflowIdHashIdentically() {
    // Issue #119: per-field pin of the NULL ≡ empty rule for workflow_id. The actor test above
    // exercises the rule once, but each length-delimited UTF-8 field (actor, workflow_id,
    // correlation_id) carries the rule independently in writeLenPrefixedUtf8 — a regression that
    // touched only the workflow_id branch (e.g. emitting a sentinel "\0" for NULL) would slip past
    // the actor test. Mirror its shape so the test corpus pins every NULL-equivalence field.
    AuditEvent base = buildSyntheticEvent();
    base.setWorkflowId(null);
    byte[] hashNull = writer.computeRowHash(base, null);
    base.setWorkflowId("");
    byte[] hashEmpty = writer.computeRowHash(base, null);
    assertThat(AuditLogChainWriter.hex(hashNull))
        .as("NULL workflow_id and empty-string workflow_id must hash identically")
        .isEqualTo(AuditLogChainWriter.hex(hashEmpty));
  }

  @Test
  void nullCorrelationIdAndEmptyCorrelationIdHashIdentically() {
    // Issue #119: per-field pin of the NULL ≡ empty rule for correlation_id. Same rationale as
    // the workflow_id test above — every length-delimited UTF-8 field needs its own explicit pin
    // so a single-branch regression cannot drift past the corpus.
    AuditEvent base = buildSyntheticEvent();
    base.setCorrelationId(null);
    byte[] hashNull = writer.computeRowHash(base, null);
    base.setCorrelationId("");
    byte[] hashEmpty = writer.computeRowHash(base, null);
    assertThat(AuditLogChainWriter.hex(hashNull))
        .as("NULL correlation_id and empty-string correlation_id must hash identically")
        .isEqualTo(AuditLogChainWriter.hex(hashEmpty));
  }

  @Test
  void recomputedRowHashesMatchFixture() throws Exception {
    JsonNode rowsNode = fixture.get("rows");
    byte[] prior = null;
    for (int i = 0; i < rowsNode.size(); i++) {
      JsonNode rowNode = rowsNode.get(i);
      AuditEvent ev = om.treeToValue(rowNode.get("event"), AuditEvent.class);

      // Chain-link sanity: the fixture's prior_row_hash_hex must match what we computed for the
      // previous row. This is a redundant check vs. the row_hash assertion below, but it catches
      // fixture-author errors where the prior_row_hash field is desynced from the row above.
      JsonNode priorNode = rowNode.get("prior_row_hash_hex");
      if (priorNode.isNull()) {
        assertThat(prior).as("row[%d] should have null prior", i).isNull();
      } else {
        assertThat(AuditLogChainWriter.hex(prior))
            .as("row[%d] prior_row_hash_hex must chain from row[%d]", i, i - 1)
            .isEqualTo(priorNode.textValue());
      }

      byte[] rowHash = writer.computeRowHash(ev, prior);
      String expected = rowNode.get("expected_row_hash_hex").textValue();
      assertThat(AuditLogChainWriter.hex(rowHash))
          .as("row[%d] (%s) row_hash must match golden vector", i, ev.getKind())
          .isEqualTo(expected);
      prior = rowHash;
    }
  }

  @Test
  void merkleRoot3LeavesMatchesFixtureOddNodeCase() throws Exception {
    JsonNode rootNode = fixture.get("merkle_root_3_leaves");
    List<byte[]> leaves = leavesFromFixture(rootNode);
    assertThat(leaves).hasSize(3); // odd-node case forces the duplicate-last rule
    byte[] root = AuditMerkleRoot.root(leaves);
    assertThat(AuditLogChainWriter.hex(root))
        .as("3-leaf Bitcoin-style duplicate-last-on-odd Merkle root")
        .isEqualTo(rootNode.get("expected_root_hex").textValue());
  }

  @Test
  void merkleRoot4LeavesMatchesFixtureEvenCase() throws Exception {
    JsonNode rootNode = fixture.get("merkle_root_4_leaves");
    List<byte[]> leaves = leavesFromFixture(rootNode);
    assertThat(leaves).hasSize(4);
    byte[] root = AuditMerkleRoot.root(leaves);
    assertThat(AuditLogChainWriter.hex(root))
        .as("4-leaf even-case Merkle root")
        .isEqualTo(rootNode.get("expected_root_hex").textValue());
  }

  @Test
  void merkleRoot1LeafReturnsLeafHash() {
    // Issue #119: degenerate 1-leaf case. AuditMerkleRoot.root([leaf]) MUST return the leaf
    // bytes unchanged — no SHA-256 round is applied at depth 0. This guards against a future
    // refactor that prematurely hashes the single leaf (e.g. emits sha256(leaf||leaf)) and pins
    // the loop-termination invariant in AuditMerkleRoot.root: when level.size() == 1, return
    // level.get(0) directly. The fixture's expected_root_hex MUST equal row[0]'s row_hash hex.
    JsonNode rootNode = fixture.get("merkle_root_1_leaf");
    List<byte[]> leaves = leavesFromFixture(rootNode);
    assertThat(leaves).hasSize(1);
    byte[] root = AuditMerkleRoot.root(leaves);
    assertThat(AuditLogChainWriter.hex(root))
        .as("1-leaf degenerate Merkle root must equal the single leaf hash unchanged")
        .isEqualTo(rootNode.get("expected_root_hex").textValue());
    // Belt-and-suspenders: explicitly check that the root equals the leaf bytes (the fixture
    // could be desynced from its own leaf reference; this catches that authoring error).
    assertThat(AuditLogChainWriter.hex(root))
        .as("1-leaf root must equal leaves.get(0) by reference")
        .isEqualTo(AuditLogChainWriter.hex(leaves.get(0)));
  }

  @Test
  void merkleRoot5LeavesMatchesFixtureOddNodeAtTwoLevels() {
    // Issue #119: 5-leaf Bitcoin-style duplicate-last-on-odd Merkle root. The 5-leaf size forces
    // the odd-node rule to fire at TWO consecutive levels (level 0: 5→3; level 1: 3→2; level 2:
    // 2→1=root). Pins the convention named in docs/ops/audit-retention.md §2 against a regression
    // where odd-node duplication only happens at the leaf layer (e.g. an early-return when
    // level.size() == 3 incorrectly assumes pairs are aligned). The fixture's expected_root_hex
    // was generated by running production AuditMerkleRoot.root(...) against the 5-leaf list.
    JsonNode rootNode = fixture.get("merkle_root_5_leaves");
    List<byte[]> leaves = leavesFromFixture(rootNode);
    assertThat(leaves).hasSize(5); // odd-node case at level 0 AND level 1
    byte[] root = AuditMerkleRoot.root(leaves);
    assertThat(AuditLogChainWriter.hex(root))
        .as("5-leaf duplicate-last-on-odd Merkle root with two-level odd-node firing")
        .isEqualTo(rootNode.get("expected_root_hex").textValue());
  }

  @Test
  void merkleRootRejectsEmptyLeafList() {
    assertThatThrownBy(() -> AuditMerkleRoot.root(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void merkleRootRejectsWrongSizeLeaf() {
    assertThatThrownBy(() -> AuditMerkleRoot.root(List.of(new byte[31])))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void computeRowHashRejectsWrongSizePriorHash() {
    AuditEvent ev = buildSyntheticEvent();
    assertThatThrownBy(() -> writer.computeRowHash(ev, new byte[31]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ----- Issue #118: deferred JCS canonical-encoding edge cases -----

  @Test
  void canonicalSubjectRejectsFloatOutsideSafeRange() {
    // 1e-4 sits below the 1e-3 JLS cutoff; 1e21 is the ECMA-262 upper threshold (exclusive).
    AuditEvent belowLow = buildSyntheticEvent();
    belowLow.setSubject(Map.of("x", 1e-4));
    assertThatThrownBy(() -> writer.computeRowHash(belowLow, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outside ECMA-262 safe range");

    AuditEvent atUpper = buildSyntheticEvent();
    atUpper.setSubject(Map.of("x", 1e21));
    assertThatThrownBy(() -> writer.computeRowHash(atUpper, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outside ECMA-262 safe range");
  }

  @Test
  void canonicalSubjectAcceptsFloatInsideSafeRange() {
    // Anchors near-lower-bound canonical bytes (0.0015) so a Double.toString drift at the 1e-3
    // cutoff surfaces as a loud test failure rather than silent canonical divergence.
    AuditEvent ev = buildSyntheticEvent();
    ev.setSubject(Map.of("x", 1.5));
    byte[] first = writer.computeRowHash(ev, null);
    byte[] second = writer.computeRowHash(ev, null);
    assertThat(AuditLogChainWriter.hex(first))
        .as("safe-range float canonicalization must be deterministic")
        .isEqualTo(AuditLogChainWriter.hex(second));

    byte[] nearLow = writer.canonicalSubjectBytes(Map.of("x", 0.0015));
    assertThat(new String(nearLow, StandardCharsets.UTF_8))
        .as("0.0015 must canonicalize as ECMA-262 decimal form")
        .isEqualTo("{\"x\":0.0015}");
  }

  @Test
  void canonicalSubjectRendersNegativeZeroAsZero() {
    // Anchors that the dedicated zero guard fires before the endsWith(".0") strip — the strip
    // alone would have produced "-0", still divergent from ECMA-262.
    byte[] out = writer.canonicalSubjectBytes(Map.of("x", -0.0));
    assertThat(new String(out, StandardCharsets.UTF_8))
        .as("-0.0 must canonicalize as ECMA-262 \"0\"")
        .isEqualTo("{\"x\":0}");
  }

  @Test
  void canonicalStringEscapesLoneSurrogates() {
    // Plan #118 item 2: lone surrogate (U+D800) must emit the six-character JSON escape \uD800
    // rather than copy the malformed UTF-16 code unit literally (which would corrupt UTF-8).
    byte[] loneOut = writer.canonicalSubjectBytes(Map.of("k", "\uD800"));
    String loneStr = new String(loneOut, StandardCharsets.UTF_8);
    assertThat(loneStr.toLowerCase(java.util.Locale.ROOT))
        .as("lone high surrogate must be emitted as the JSON escape \\uD800")
        .contains("\\ud800");

    // Lone low surrogate (U+DC00) takes the same escape path via the isHighSurrogate=false branch
    // — pin it so a regression that treated a low surrogate as a valid pair starter would fail.
    byte[] loneLoOut = writer.canonicalSubjectBytes(Map.of("k", "\uDC00"));
    assertThat(new String(loneLoOut, StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT))
        .as("lone low surrogate must be emitted as the JSON escape \\uDC00")
        .contains("\\udc00");

    // Valid surrogate pair (U+1D11E MUSICAL SYMBOL G CLEF) must round-trip as literal UTF-8 —
    // not escape-encoded — so legitimate non-BMP characters survive canonicalization.
    String gClef = new String(Character.toChars(0x1D11E));
    byte[] pairOut = writer.canonicalSubjectBytes(Map.of("k", gClef));
    String pairStr = new String(pairOut, StandardCharsets.UTF_8);
    assertThat(pairStr).as("valid surrogate pair must round-trip as literal UTF-8").contains(gClef);
    assertThat(pairStr.toLowerCase(java.util.Locale.ROOT))
        .as("valid surrogate pair must not be escape-encoded")
        .doesNotContain("\\ud83d")
        .doesNotContain("\\ud834");
  }

  @Test
  void canonicalRejectsBinaryNode() {
    // Plan #118 item 3: BinaryNode is intentionally rejected. Pin the existing throw behaviour
    // so a future allowlist expansion is a deliberate, test-visible change. Jackson's
    // valueToTree(byte[]) yields a BinaryNode, which is exactly what the orchestrator's
    // canonicalSubjectBytes path would hit if a future caller stuffed bytes into the subject map.
    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("blob", new byte[] {1, 2, 3});
    assertThatThrownBy(() -> writer.canonicalSubjectBytes(subject))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported JCS node type");
  }

  private static List<byte[]> leavesFromFixture(JsonNode merkleNode) {
    JsonNode rowsNode = fixture.get("rows");
    JsonNode indices = merkleNode.get("leaf_row_indices");
    List<byte[]> leaves = new ArrayList<>(indices.size());
    for (JsonNode idxNode : indices) {
      int idx = idxNode.asInt();
      String hex = rowsNode.get(idx).get("expected_row_hash_hex").textValue();
      leaves.add(AuditLogChainWriter.unhex(hex));
    }
    return leaves;
  }

  private static AuditEvent buildSyntheticEvent() {
    AuditEvent ev = new AuditEvent();
    ev.setSchemaVersion(1L);
    ev.setTenantId("dev");
    ev.setStrategyId("copytrade-v1");
    ev.setEventId("00000000-0000-4000-8000-00000000aaaa");
    ev.setOccurredAt(java.time.OffsetDateTime.parse("2026-05-18T07:00:00Z"));
    ev.setKind("SignalReceived");
    ev.setWorkflowId("wf");
    ev.setCorrelationId("c");
    ev.setSubject(java.util.Map.of("k", "v"));
    return ev;
  }
}
