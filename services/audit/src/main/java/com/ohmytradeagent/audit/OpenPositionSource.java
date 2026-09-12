package com.ohmytradeagent.audit;

import java.util.Set;

/**
 * Who is still holding a position right now, expressed as {@code correlation_id}s.
 *
 * <p>Why this port has to exist: an open position and a LOST terminal-close event are
 * indistinguishable in {@code audit_log}. Measured on prod_real — a position entered 2026-09-09 and
 * still open three days later has exactly five events, all on the entry day, and nothing since. An
 * open position is audit-SILENT after entry. So "entry present, nothing after" cannot be read as a
 * fault without asking something outside the log.
 *
 * <p>Positions live in Temporal workflows (there is no {@code positions} table), so that is what
 * gets asked. This keeps {@link LedgerRederiver} pure: the re-deriver reports which lifecycles are
 * unclosed, and the verifier decides which of those are merely OPEN versus genuinely missing a
 * close.
 */
public interface OpenPositionSource {

  /**
   * The {@code correlation_id} of every position still open for this (tenant, strategy).
   *
   * <p>Implementations MUST fail loudly rather than return an empty set when the answer cannot be
   * determined: an empty set is read as "nothing is open", which would turn every unclosed
   * lifecycle into a divergence. "Cannot verify" is not "verified".
   */
  Set<String> openCorrelationIds(String tenantId, String strategyId);
}
