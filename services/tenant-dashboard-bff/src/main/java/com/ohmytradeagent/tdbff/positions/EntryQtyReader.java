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
 * How many contracts a position ORIGINALLY entered, so Holdings can show "entered -> remaining"
 * rather than only what is left.
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
public class EntryQtyReader {

  private static final Logger log = LoggerFactory.getLogger(EntryQtyReader.class);

  private final DSLContext orchestratorDsl;

  public EntryQtyReader(@Qualifier("orchestratorDsl") DSLContext orchestratorDsl) {
    this.orchestratorDsl = orchestratorDsl;
  }

  /** Originally-entered contracts keyed by POSITION WORKFLOW ID. */
  public Map<String, Long> enteredQtyByWorkflowId(
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
                  + "   FILTER (WHERE kind = 'PositionEntryIncreased'), 0) AS entered"
                  + " FROM audit_log"
                  + " WHERE tenant_id = ? AND correlation_id = ANY(?)"
                  + "   AND kind IN ('EntryFilled', 'PositionEntryIncreased')"
                  + " GROUP BY correlation_id",
              tenantId,
              workflowsBySignal.keySet().toArray(new String[0]));
      Map<String, Long> out = new HashMap<>();
      for (Record r : rows) {
        String signalId = r.get(0, String.class);
        Long entered = r.get(1, Long.class);
        if (entered == null || entered <= 0) {
          continue;
        }
        for (String wf : workflowsBySignal.getOrDefault(signalId, List.of())) {
          out.put(wf, entered);
        }
      }
      return out;
    } catch (RuntimeException e) {
      log.debug("entry-qty read failed tenant={}: {}", tenantId, e.toString());
      return Map.of();
    }
  }
}
