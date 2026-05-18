package com.ohmytradeagent.orchestrator.activities;

import java.util.ArrayList;
import java.util.List;

/**
 * Issue #85: pure-Java daily Merkle-root helper for the audit-log chain. Pins the
 * <strong>Bitcoin-style duplicate-last-on-odd</strong> convention:
 *
 * <ul>
 *   <li>SHA-256 over {@code node || node} when the right child is missing at any level.
 *   <li>Same SHA-256 hash function for both leaf and internal nodes.
 *   <li>No domain-separation prefixes.
 * </ul>
 *
 * <p>This convention is named in {@code docs/ops/audit-retention.md §2} ("Daily Merkle root"
 * subsection) and is the executable counterpart to that pin. The class lives here so the daily
 * Merkle-root scheduled job (still out-of-scope per the issue body) can consume it without
 * cross-package wiring when it lands.
 */
public final class AuditMerkleRoot {

  private AuditMerkleRoot() {}

  /**
   * Compute the Merkle root over an ordered list of leaf hashes. Leaves are the {@code row_hash}
   * column values for a single {@code (tenant_id, strategy_id, period_date)} cohort, ordered by
   * {@code id ASC}.
   *
   * @param leaves SHA-256 leaf hashes (32 bytes each). MUST be non-empty.
   * @return 32-byte SHA-256 Merkle root.
   */
  public static byte[] root(List<byte[]> leaves) {
    if (leaves == null || leaves.isEmpty()) {
      throw new IllegalArgumentException("Merkle root requires at least one leaf");
    }
    List<byte[]> level = new ArrayList<>(leaves.size());
    for (byte[] leaf : leaves) {
      if (leaf == null || leaf.length != 32) {
        throw new IllegalArgumentException("each leaf must be 32 bytes (SHA-256)");
      }
      level.add(leaf);
    }
    while (level.size() > 1) {
      List<byte[]> next = new ArrayList<>((level.size() + 1) / 2);
      for (int i = 0; i < level.size(); i += 2) {
        byte[] left = level.get(i);
        byte[] right = (i + 1 < level.size()) ? level.get(i + 1) : left; // duplicate-last-on-odd
        byte[] pair = new byte[64];
        System.arraycopy(left, 0, pair, 0, 32);
        System.arraycopy(right, 0, pair, 32, 32);
        next.add(AuditLogChainWriter.sha256(pair));
      }
      level = next;
    }
    return level.get(0);
  }
}
