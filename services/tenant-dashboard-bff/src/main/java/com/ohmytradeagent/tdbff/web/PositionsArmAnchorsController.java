package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.positions.TradeContextPeakReader;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * #778 arm-anchor choice: {@code GET /api/positions/arm-anchors} returns BOTH candidate trailing-
 * stop anchors for one position, so the /live "Stop-loss" control can offer the operator a real
 * choice before {@code POST /arm-trail}:
 *
 * <ul>
 *   <li>{@code recent_anchor} — today's behavior. The peak/stop are {@code null} ON PURPOSE: the
 *       workflow resolves this anchor itself (max of its tracked peak and a fresh quote) when
 *       {@code peak_premium} is omitted from the arm, and this endpoint does not re-derive workflow
 *       internals — a re-derived number would be a second, subtly different truth.
 *   <li>{@code true_peak_anchor} — the TRUE peak since entry, from the #783 {@code
 *       trade_context.mfe_premium} recorder, with the stop it implies ({@code peak * (1 -
 *       giveback)}). {@code null} whenever no usable value exists (datasource off, table absent
 *       while PR #786 is unmerged, row absent while the recorder is dark, mfe null/non-positive) —
 *       in which case the dashboard shows exactly today's UI.
 * </ul>
 *
 * <p>A SEPARATE controller from {@link PositionsController} on purpose: this is a read that needs
 * the {@code TradeContextPeakReader} dependency, and folding it into the write controller would
 * ripple that dependency through every existing positions WebMvc test context. Read-only and
 * fail-soft, so it carries no dark-launch flag of its own — the arm WRITE remains gated by {@code
 * positions.arm-trail.write-enabled}, and without that write this read leads nowhere.
 */
@RestController
@RequestMapping("/api/positions")
public class PositionsArmAnchorsController {

  private final TenantContext ctx;
  private final TradeContextPeakReader peaks;

  public PositionsArmAnchorsController(TenantContext ctx, TradeContextPeakReader peaks) {
    this.ctx = ctx;
    this.peaks = peaks;
  }

  @GetMapping("/arm-anchors")
  public ResponseEntity<Map<String, Object>> anchors(
      HttpServletRequest req,
      @RequestParam("workflow_id") String workflowId,
      @RequestParam("giveback_pct") Double givebackPct) {
    String tenant = ctx.tenantId(req); // fail-closed 401 — the tenant is NEVER a client parameter

    if (workflowId == null || workflowId.isBlank()) {
      throw new IllegalArgumentException("workflow_id is required");
    }
    // Same bounds and message as POST /arm-trail: an anchor preview for a giveback the arm would
    // refuse is a preview of nothing.
    if (givebackPct == null || givebackPct <= 0.0 || givebackPct > 0.5) {
      throw new IllegalArgumentException(
          "giveback_pct must be between 0 and 0.5 (0 exclusive, 0.5 inclusive)");
    }

    // Same tenant-boundary + kind guard as every position write: this read names a specific
    // workflow, and cross-tenant position metadata (peak premium) must not leak either.
    ResponseEntity<Map<String, Object>> refusal =
        WorkflowWriteGuards.refuseUnlessTenantOwned(
            tenant, workflowId, "/pos/", "not_a_position_workflow_id");
    if (refusal != null) {
      return refusal;
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", tenant);
    body.put("workflow_id", workflowId);
    body.put("giveback_pct", givebackPct);

    Map<String, Object> recent = new LinkedHashMap<>();
    recent.put("peak", null);
    recent.put("stop", null);
    recent.put("source", "workflow_resolved");
    body.put("recent_anchor", recent);

    BigDecimal mfe = peaks.mfePremium(tenant, workflowId);
    if (mfe == null) {
      body.put("true_peak_anchor", null);
    } else {
      // Cent-rounded fire threshold for the requested giveback — the same rounding the dashboard's
      // stopPriceFor applies, so the two never render different prices for the same choice.
      BigDecimal stop =
          mfe.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(givebackPct)))
              .setScale(2, RoundingMode.HALF_UP);
      Map<String, Object> truePeak = new LinkedHashMap<>();
      truePeak.put("peak", mfe);
      truePeak.put("stop", stop);
      truePeak.put("source", "trade_context_mfe");
      body.put("true_peak_anchor", truePeak);
    }
    return ResponseEntity.ok(body);
  }
}
