package com.ohmytradeagent.tdbff.positions;

import java.math.BigDecimal;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Reads a position's TRUE peak-since-entry ({@code trade_context.mfe_premium}, the #783 recorder's
 * per-poll bid ratchet) from the dashboard DB, for the #778 arm-anchor choice on /live.
 *
 * <p>FAIL-SOFT BY CONTRACT: every failure mode returns {@code null} — dashboard-writer datasource
 * absent (the {@code dashboardWriterDsl} bean is conditional on {@code dashboard.writer.enabled}),
 * {@code trade_context} table not yet migrated (#783 / PR #786 is unmerged, so the relation may not
 * exist at runtime), row absent (the recorder ships dark), {@code mfe_premium} null or
 * non-positive, or an unparseable workflow id. A {@code null} here degrades the arm flow to exactly
 * today's behavior (workflow-resolved anchor); it must never fail an arm.
 */
@Component
public class TradeContextPeakReader {

  private static final Logger log = LoggerFactory.getLogger(TradeContextPeakReader.class);

  /** Null when the dashboard-writer datasource is not enabled on this cluster. */
  private final DSLContext dashboardDsl;

  public TradeContextPeakReader(
      @Qualifier("dashboardWriterDsl") Optional<DSLContext> dashboardDsl) {
    this.dashboardDsl = dashboardDsl.orElse(null);
  }

  /**
   * The recorded max-favorable-excursion premium for the position, or {@code null} when no usable
   * value exists (see class doc). Keyed {@code (tenant_id, signal_id)} — the same key the #783
   * recorder writes — with the signal id parsed from the position workflow id.
   */
  public BigDecimal mfePremium(String tenantId, String positionWorkflowId) {
    if (dashboardDsl == null) {
      return null;
    }
    String signalId =
        com.ohmytradeagent.contract.identity.WorkflowIds.entrySignalIdFromPosition(
            positionWorkflowId);
    if (signalId == null) {
      return null;
    }
    try {
      Record row =
          dashboardDsl.fetchOne(
              "SELECT mfe_premium FROM trade_context WHERE tenant_id = ? AND signal_id = ?",
              tenantId,
              signalId);
      if (row == null) {
        return null;
      }
      BigDecimal mfe = row.get(0, BigDecimal.class);
      // A non-positive MFE cannot anchor a stop: fire = peak * (1 - giveback) would be <= 0.
      if (mfe == null || mfe.signum() <= 0) {
        return null;
      }
      return mfe;
    } catch (RuntimeException e) {
      // jOOQ wraps every SQLException (including 42P01 "relation trade_context does not exist" —
      // expected while #786 is unmerged) in a DataAccessException. Any read failure means "no
      // offered anchor", never a failed arm flow.
      log.debug(
          "trade_context peak read failed (offering recent-only) tenant={} wf={}: {}",
          tenantId,
          positionWorkflowId,
          e.toString());
      return null;
    }
  }
}
