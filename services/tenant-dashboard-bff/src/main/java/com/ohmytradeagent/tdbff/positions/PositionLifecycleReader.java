package com.ohmytradeagent.tdbff.positions;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * A position's entry and exit history from the audit trail: how much it ORIGINALLY entered (so
 * Holdings can show "entered -> remaining"), and how much it has already SOLD and for what (so the
 * row can show total money made, not only the paper gain on what is left).
 *
 * <p>The second half matters more than it sounds. {@code unrealized_pl} is {@code (mark - entry) x
 * remaining_qty x 100} — the REMAINING lot only — so a position that has been trimmed reports a
 * fraction of what it actually made. Live example: a prod_real GOOGL position entered 10 at 2.76
 * and sold 3 at 3.75, 3 at 4.25 and 2 at 6.07. It has banked $1,406, and the row shows roughly
 * $168.
 *
 * <p>Derived from the audit trail, which is the only correct source:
 *
 * <ul>
 *   <li>{@code trade_context.entry_qty} is NOT it. Its own schema calls it "remaining qty at first
 *       observation", and live data proves the difference — a prod_real SMCI position reads 2 there
 *       while it actually entered 21.
 *   <li>a lone {@code EntryFilled} is not it either: an entry can GROW after the first fill (#738
 *       books the rest of our own entry report as {@code PositionEntryIncreased}). That same
 *       position is EntryFilled 2 then +19.
 * </ul>
 *
 * So entered = sum(EntryFilled.filled_qty) + sum(PositionEntryIncreased.qty_added), per entry
 * signal. Keyed on {@code correlation_id}, which both kinds carry as the entry signal id — the same
 * key {@link TradeContextSpotReader} uses, parsed from the position workflow id.
 *
 * <p>FAIL-SOFT: any failure yields an empty map and the column falls back to showing the remaining
 * qty alone. A missing number must never cost the operator their Holdings table.
 */
@Component
public class PositionLifecycleReader {

  private static final Logger log = LoggerFactory.getLogger(PositionLifecycleReader.class);

  private final DSLContext orchestratorDsl;

  public PositionLifecycleReader(@Qualifier("orchestratorDsl") DSLContext orchestratorDsl) {
    this.orchestratorDsl = orchestratorDsl;
  }

  /**
   * What a position entered, what it has already sold, and what those sales brought in — keyed by
   * POSITION WORKFLOW ID.
   *
   * <p>{@code exitedQty} and {@code proceeds} count ONLY exits that carried a price. An exit whose
   * broker report had no avg_fill_price (#753 leaves it null) is excluded from BOTH, so the
   * realized figure derived from them stays internally consistent rather than counting quantity it
   * cannot value.
   */
  public record Lifecycle(long enteredQty, long exitedQty, java.math.BigDecimal proceeds) {}

  /** One pass over the audit trail for every open position in the list. */
  public Map<String, Lifecycle> lifecycleByWorkflowId(
      String tenantId, Collection<String> positionWorkflowIds) {
    if (positionWorkflowIds.isEmpty()) {
      return Map.of();
    }
    // A LIST per signal: two open positions can parse to the same entry signal id, and both should
    // report the same entered quantity rather than one silently losing it.
    Map<String, List<String>> workflowsBySignal = new LinkedHashMap<>();
    for (String wf : positionWorkflowIds) {
      String signalId = WorkflowIds.entrySignalIdFromPosition(wf);
      if (signalId != null) {
        workflowsBySignal.computeIfAbsent(signalId, k -> new ArrayList<>()).add(wf);
      }
    }
    if (workflowsBySignal.isEmpty()) {
      return Map.of();
    }
    try {
      Result<Record> rows =
          orchestratorDsl.fetch(
              "SELECT correlation_id,"
                  + " COALESCE(SUM((subject->>'filled_qty')::bigint)"
                  + "   FILTER (WHERE kind = 'EntryFilled'), 0)"
                  + " + COALESCE(SUM((subject->>'qty_added')::bigint)"
                  + "   FILTER (WHERE kind = 'PositionEntryIncreased'), 0) AS entered,"
                  + " COALESCE(SUM((subject->>'qty_filled')::bigint)"
                  + "   FILTER (WHERE kind = 'PartialExitFilled'"
                  + "     AND subject->>'avg_fill_price' IS NOT NULL), 0) AS exited,"
                  + " COALESCE(SUM((subject->>'avg_fill_price')::numeric"
                  + "   * (subject->>'qty_filled')::bigint)"
                  + "   FILTER (WHERE kind = 'PartialExitFilled'"
                  + "     AND subject->>'avg_fill_price' IS NOT NULL), 0) AS proceeds"
                  + " FROM audit_log"
                  + " WHERE tenant_id = ? AND correlation_id = ANY(?)"
                  + "   AND kind IN ('EntryFilled', 'PositionEntryIncreased', 'PartialExitFilled')"
                  + " GROUP BY correlation_id",
              tenantId,
              workflowsBySignal.keySet().toArray(new String[0]));
      Map<String, Lifecycle> out = new HashMap<>();
      for (Record r : rows) {
        String signalId = r.get(0, String.class);
        Long entered = r.get(1, Long.class);
        Long exited = r.get(2, Long.class);
        java.math.BigDecimal proceeds = r.get(3, java.math.BigDecimal.class);
        if (entered == null || entered <= 0) {
          continue;
        }
        Lifecycle lc =
            new Lifecycle(
                entered,
                exited == null ? 0L : exited,
                proceeds == null ? java.math.BigDecimal.ZERO : proceeds);
        for (String wf : workflowsBySignal.getOrDefault(signalId, List.of())) {
          out.put(wf, lc);
        }
      }
      return out;
    } catch (RuntimeException e) {
      log.debug("position-lifecycle read failed tenant={}: {}", tenantId, e.toString());
      return Map.of();
    }
  }
}
