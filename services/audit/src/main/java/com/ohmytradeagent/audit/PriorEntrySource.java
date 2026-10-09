package com.ohmytradeagent.audit;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Which of a set of lifecycles had an ENTRY before the scored window (#925).
 *
 * <p>Why this port has to exist: the verifier scores one window, so a hard close whose position was
 * entered on an earlier day has no entry in the slice and looked like an orphan close. Measured:
 * all 8 {@code ORPHAN_CLOSE_WITHOUT_ENTRY} divergences of the 09-30..10-02 runs were complete
 * multi-day lifecycles with their entries 1-11 days before the window. The answer is in {@code
 * audit_log} itself, just outside the slice — the close-side counterpart of {@link
 * OpenPositionSource}, which covers an entry whose close is outside the slice.
 */
public interface PriorEntrySource {

  /**
   * The subset of {@code correlationIds} that have an {@link AuditEventKinds#ENTRY_KINDS} event for
   * this (tenant, strategy) with {@code occurred_at} in {@code [fromInclusive, toExclusive)}.
   *
   * <p>Implementations MUST fail loudly rather than return an empty set when the answer cannot be
   * determined: an empty set turns every cross-window close into a divergence.
   */
  Set<String> correlationIdsWithEntry(
      String tenantId,
      String strategyId,
      Set<String> correlationIds,
      OffsetDateTime fromInclusive,
      OffsetDateTime toExclusive);
}
