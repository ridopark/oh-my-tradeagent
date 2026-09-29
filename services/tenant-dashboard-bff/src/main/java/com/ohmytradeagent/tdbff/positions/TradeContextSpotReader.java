package com.ohmytradeagent.tdbff.positions;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Reads each open position's UNDERLYING PRICE AT ENTRY ({@code trade_context.underlying_spot}, the
 * #783 recorder's one-shot entry snapshot) for the /live Holdings table.
 *
 * <p>This is the only place that number exists. The equity's price at the entry instant is not
 * otherwise recoverable from anything the dashboard reads: the workflow keeps premium, not spot,
 * and re-deriving it would be a historical market-data call per position per page render.
 *
 * <p>BATCHED deliberately — one query for the whole holdings list, not one per row. Holdings
 * renders on every /live load and the portfolio read already fans out to the broker.
 *
 * <p>FAIL-SOFT BY CONTRACT, mirroring {@link TradeContextPeakReader}: every failure yields an empty
 * map and the column degrades to "—". The datasource is conditional on {@code
 * dashboard.writer.enabled}, the row is absent for positions entered before the recorder was
 * enabled (nothing before 2026-08-22), and the SELECT grant arrived in V14 — a cluster
 * mid-migration answers 42501. None of those may cost the operator their Holdings table.
 */
@Component
public class TradeContextSpotReader {

  private static final Logger log = LoggerFactory.getLogger(TradeContextSpotReader.class);

  /** Null when the dashboard-writer datasource is not enabled on this cluster. */
  private final DSLContext dashboardDsl;

  public TradeContextSpotReader(
      @Qualifier("dashboardWriterDsl") Optional<DSLContext> dashboardDsl) {
    this.dashboardDsl = dashboardDsl.orElse(null);
  }

  /**
   * Underlying spot at entry, keyed by POSITION WORKFLOW ID so the caller can attach it to the row
   * it already holds. Two positions can share a contract symbol (a manual entry alongside a copied
   * one, live today), so the contract is not a usable key; the workflow id is.
   *
   * <p>Keyed in the table by {@code (tenant_id, signal_id)} — the recorder's key — with the signal
   * id parsed out of each position workflow id.
   */
  public Map<String, BigDecimal> entrySpotByWorkflowId(
      String tenantId, Collection<String> positionWorkflowIds) {
    if (dashboardDsl == null || positionWorkflowIds.isEmpty()) {
      return Map.of();
    }
    // signal_id -> the workflow ids that parse to it, so the result can be re-keyed without a
    // second parse. A LIST, not a single value: two open positions CAN share a signal id (the same
    // manual/copy id across strategies), and overwriting would silently drop the entry spot from
    // whichever row lost the race. The table holds one row per (tenant, signal), so both rows
    // legitimately take the same spot.
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
          dashboardDsl.fetch(
              "SELECT signal_id, underlying_spot FROM trade_context"
                  + " WHERE tenant_id = ? AND signal_id = ANY(?)",
              tenantId,
              workflowsBySignal.keySet().toArray(new String[0]));
      Map<String, BigDecimal> out = new HashMap<>();
      for (Record r : rows) {
        String signalId = r.get(0, String.class);
        BigDecimal spot = r.get(1, BigDecimal.class);
        if (spot == null) {
          continue;
        }
        for (String wf : workflowsBySignal.getOrDefault(signalId, List.of())) {
          out.put(wf, spot);
        }
      }
      return out;
    } catch (RuntimeException e) {
      // jOOQ wraps every SQLException; 42501 (no SELECT grant, pre-V14) and 42P01 (table absent on
      // a cluster that never ran the recorder) both land here. No entry spot is a blank cell.
      log.debug("trade_context spot read failed tenant={}: {}", tenantId, e.toString());
      return Map.of();
    }
  }
}
