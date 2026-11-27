package com.ohmytradeagent.tdbff.preview;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
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

  /**
   * One priced entry fill (or add-on): which signal and contract, how many, at what price, when.
   */
  record EntryFill(String signalId, String occ, long qty, BigDecimal price, Instant at) {}

  /**
   * Every priced entry fill for the given signal ids, one row each, so the caller can build a cost
   * basis per CONTRACT from only the fills it is allowed to show. Rows without a contract symbol
   * are skipped: they cannot be attributed.
   */
  List<EntryFill> entryFills(String tenantId, Collection<String> entrySignalIds) {
    List<EntryFill> out = new ArrayList<>();
    if (entrySignalIds.isEmpty()) {
      return out;
    }
    for (Record r :
        orchestratorDsl.fetch(
            "SELECT correlation_id, subject->>'option_symbol',"
                + " COALESCE((subject->>'filled_qty')::bigint, (subject->>'qty_added')::bigint),"
                + " (subject->>'avg_fill_price')::numeric, occurred_at FROM audit_log"
                + " WHERE tenant_id = ? AND correlation_id = ANY(?)"
                + " AND kind IN ('EntryFilled', 'PositionEntryIncreased')"
                + " AND subject->>'avg_fill_price' IS NOT NULL"
                + " AND subject->>'option_symbol' IS NOT NULL",
            tenantId,
            entrySignalIds.toArray(new String[0]))) {
      Long qty = r.get(2, Long.class);
      BigDecimal price = r.get(3, BigDecimal.class);
      Timestamp at = r.get(4, Timestamp.class);
      if (qty != null && qty > 0 && price != null && at != null) {
        out.add(
            new EntryFill(
                r.get(0, String.class), r.get(1, String.class), qty, price, at.toInstant()));
      }
    }
    return out;
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
