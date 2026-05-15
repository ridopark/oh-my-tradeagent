package com.ohmytradeagent.apigateway.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only audit-log view. jOOQ against the orchestrator's {@code audit_log} table (same DB).
 *
 * <p>Phase 5 ships: paginated by {@code since} (ISO-8601) + {@code limit} (default 100, max 500),
 * filtered by {@code (tenant_id, strategy_id)} from headers, optional {@code kind} query param.
 */
@RestController
@RequestMapping("/audit")
public class AuditController {

  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 500;

  private final DSLContext dsl;
  private final TenantContext ctx;

  public AuditController(DSLContext dsl, TenantContext ctx) {
    this.dsl = dsl;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> list(
      HttpServletRequest req,
      @RequestParam(value = "since", required = false) String sinceIso,
      @RequestParam(value = "kind", required = false) String kind,
      @RequestParam(value = "limit", required = false, defaultValue = "100") int limit) {
    String tenant = ctx.tenantId(req);
    String strategy = ctx.strategyId(req);
    int cappedLimit = Math.max(1, Math.min(MAX_LIMIT, limit <= 0 ? DEFAULT_LIMIT : limit));
    OffsetDateTime since =
        sinceIso == null || sinceIso.isBlank() ? null : OffsetDateTime.parse(sinceIso);

    var cond = DSL.field("tenant_id").eq(tenant).and(DSL.field("strategy_id").eq(strategy));
    if (since != null) {
      cond = cond.and(DSL.field("occurred_at").greaterOrEqual(DSL.val(since)));
    }
    if (kind != null && !kind.isBlank()) {
      cond = cond.and(DSL.field("kind").eq(kind));
    }

    List<Map<String, Object>> rows =
        dsl
            .select(
                DSL.field("event_id"),
                DSL.field("occurred_at"),
                DSL.field("kind"),
                DSL.field("actor"),
                DSL.field("workflow_id"),
                DSL.field("correlation_id"),
                DSL.field("subject").cast(String.class).as("subject_json"))
            .from(DSL.table("audit_log"))
            .where(cond)
            .orderBy(DSL.field("occurred_at").desc(), DSL.field("id").desc())
            .limit(cappedLimit)
            .fetch()
            .stream()
            .map(AuditController::row)
            .toList();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", tenant);
    body.put("strategy_id", strategy);
    body.put("limit", cappedLimit);
    body.put("count", rows.size());
    body.put("items", rows);
    return ResponseEntity.ok(body);
  }

  private static Map<String, Object> row(Record r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("event_id", r.get("event_id"));
    m.put("occurred_at", r.get("occurred_at"));
    m.put("kind", r.get("kind"));
    m.put("actor", r.get("actor"));
    m.put("workflow_id", r.get("workflow_id"));
    m.put("correlation_id", r.get("correlation_id"));
    m.put("subject", r.get("subject_json"));
    return Collections.unmodifiableMap(m);
  }
}
