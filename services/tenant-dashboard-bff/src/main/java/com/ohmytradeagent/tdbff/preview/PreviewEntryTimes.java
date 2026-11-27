package com.ohmytradeagent.tdbff.preview;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * When each open position was entered, from the audit trail.
 *
 * <p>Keyed by entry signal id AND contract, never by signal id alone: every leg of a day's
 * watchlist shares one signal id, so the id alone would date a leg bought seconds ago by the first
 * leg of the morning. The LATEST {@code EntryFilled} is taken because the same contract can be
 * re-entered under the same id later in the day; scale-ins are {@code PositionEntryIncreased} and
 * do not move it.
 */
@Component
class PreviewEntryTimes {

  private final DSLContext orchestratorDsl;

  PreviewEntryTimes(@Qualifier("orchestratorDsl") DSLContext orchestratorDsl) {
    this.orchestratorDsl = orchestratorDsl;
  }

  /** The lookup key for one position: its entry signal id and its contract. */
  static String key(String entrySignalId, String occ) {
    return entrySignalId + "|" + PreviewProjection.compact(occ);
  }

  /** {@link #key} → entry time. A position with no {@code EntryFilled} row is simply absent. */
  Map<String, Instant> entryAt(String tenantId, Collection<String> entrySignalIds) {
    Map<String, Instant> out = new HashMap<>();
    if (entrySignalIds.isEmpty()) {
      return out;
    }
    for (Record r :
        orchestratorDsl.fetch(
            "SELECT correlation_id, subject->>'option_symbol', MAX(occurred_at) FROM audit_log"
                + " WHERE tenant_id = ? AND correlation_id = ANY(?) AND kind = 'EntryFilled'"
                + " GROUP BY correlation_id, subject->>'option_symbol'",
            tenantId,
            entrySignalIds.toArray(new String[0]))) {
      Timestamp at = r.get(2, Timestamp.class);
      String occ = r.get(1, String.class);
      if (at != null && PreviewProjection.compact(occ) != null) {
        out.put(key(r.get(0, String.class), occ), at.toInstant());
      }
    }
    return out;
  }
}
