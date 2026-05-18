package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.contract.AuditEvent;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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
