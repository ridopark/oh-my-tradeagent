package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.promotion.LivePromotionReader;
import com.ohmytradeagent.tdbff.promotion.LivePromotionReader.PromotionStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/live-promotion} — whether the authenticated tenant's live strategies are actually
 * cleared to place real-money orders. Backs the /live activation banner.
 *
 * <p>Read-only; see {@link LivePromotionReader} for why this state needs to be visible at all (a
 * seven-day silent trading halt in 2026-09 that /live rendered as entirely normal).
 *
 * <p>{@code overall} is computed HERE rather than in the client so the severity ordering lives in
 * one place: a tenant with a stale copytrade strategy and a healthy watchlist one must render as
 * blocked, not as a mixture the banner has to re-rank.
 */
@RestController
@RequestMapping("/api/live-promotion")
public class LivePromotionController {

  private static final Logger log = LoggerFactory.getLogger(LivePromotionController.class);

  /** Every status the reader can produce, most severe first. */
  private static final List<String> BY_SEVERITY =
      List.of("stale", "absent", "deactivated", "config_changed", "unknown", "expiring", "active");

  private final LivePromotionReader reader;
  private final TenantContext ctx;

  public LivePromotionController(LivePromotionReader reader, TenantContext ctx) {
    this.reader = reader;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> get(HttpServletRequest req) {
    String tenant = ctx.tenantId(req);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", tenant);

    List<PromotionStatus> statuses;
    try {
      statuses = reader.statuses(tenant, OffsetDateTime.now());
    } catch (RuntimeException e) {
      // Could not even enumerate the live strategies. "none" would render as an all-clear.
      log.warn("live-promotion strategy enumeration failed tenant={}: {}", tenant, e.toString());
      body.put("overall", "unknown");
      body.put("strategies", List.of());
      return ResponseEntity.ok(body);
    }
    body.put("overall", overall(statuses));
    body.put("strategies", statuses.stream().map(LivePromotionController::row).toList());
    return ResponseEntity.ok(body);
  }

  /**
   * The most severe status across the tenant's live strategies, or {@code "none"} when it has none
   * (a paper-only tenant — nothing to say, and the banner renders nothing).
   *
   * <p>Severity order, most severe first: {@code stale}, {@code absent}, {@code deactivated} and
   * {@code config_changed} all mean orders are being REFUSED right now; {@code unknown} means we
   * could not tell, which outranks {@code expiring} because an all-clear we cannot verify is worse
   * than a deadline we can see coming.
   */
  static String overall(List<PromotionStatus> statuses) {
    if (statuses.isEmpty()) {
      return "none";
    }
    return BY_SEVERITY.stream()
        .filter(candidate -> statuses.stream().anyMatch(s -> candidate.equals(s.status())))
        .findFirst()
        .orElseThrow();
  }

  private static Map<String, Object> row(PromotionStatus s) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("strategy_id", s.strategyId());
    m.put("broker_target", s.brokerTarget());
    m.put("status", s.status());
    m.put("expires_at", s.expiresAt());
    m.put("days_remaining", s.daysRemaining());
    return m;
  }
}
