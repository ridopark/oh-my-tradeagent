package com.ohmytradeagent.tdbff.trades;

// audit_log select COPIED FROM services/api-gateway/.../web/AuditController.java — keep in sync.
// Narrowed to the two FILL kinds a tenant cares about (EntryFilled + PartialExitFilled) and scoped
// to the tenant's whole strategy set (strategy_id IN (...)).
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Read-only view of a tenant's fills from the orchestrator's {@code audit_log}. */
@Component
public class TradesReader {

  static final int DEFAULT_LIMIT = 100;
  static final int MAX_LIMIT = 500;

  /**
   * The fill kinds shown as "trades": confirmed entry + partial-exit fills. These literals mirror
   * {@code services/audit/.../AuditEventKinds.java} ({@code ENTRY_KINDS} member {@code
   * EntryFilled}, {@code PARTIAL_EXIT_FILL_KINDS} member {@code PartialExitFilled}) — kept as
   * literals so the BFF stays dependency-light (contract-java only, not the whole audit module).
   */
  private static final List<String> FILL_KINDS = List.of("EntryFilled", "PartialExitFilled");

  private final DSLContext orchestratorDsl;

  public TradesReader(@Qualifier("orchestratorDsl") DSLContext orchestratorDsl) {
    this.orchestratorDsl = orchestratorDsl;
  }

  /**
   * Fills for the tenant across {@code strategyIds}, newest first. {@code sinceIso} (optional,
   * ISO-8601) lower-bounds {@code occurred_at}; {@code limit} defaults to 100, capped at 500.
   */
  public List<Map<String, Object>> trades(
      String tenantId, List<String> strategyIds, String sinceIso, int limit) {
    if (strategyIds.isEmpty()) {
      return List.of();
    }
    int cappedLimit = Math.max(1, Math.min(MAX_LIMIT, limit <= 0 ? DEFAULT_LIMIT : limit));
    OffsetDateTime since =
        sinceIso == null || sinceIso.isBlank() ? null : OffsetDateTime.parse(sinceIso);

    var cond =
        DSL.field("tenant_id")
            .eq(tenantId)
            .and(DSL.field("strategy_id").in(strategyIds))
            .and(DSL.field("kind").in(FILL_KINDS));
    if (since != null) {
      cond = cond.and(DSL.field("occurred_at").greaterOrEqual(DSL.val(since)));
    }

    return orchestratorDsl
        .select(
            DSL.field("event_id"),
            DSL.field("occurred_at"),
            DSL.field("kind"),
            DSL.field("actor"),
            DSL.field("strategy_id"),
            DSL.field("workflow_id"),
            DSL.field("correlation_id"),
            DSL.field("subject").cast(String.class).as("subject_json"))
        .from(DSL.table("audit_log"))
        .where(cond)
        .orderBy(DSL.field("occurred_at").desc(), DSL.field("id").desc())
        .limit(cappedLimit)
        .fetch()
        .stream()
        .map(TradesReader::row)
        .toList();
  }

  private static Map<String, Object> row(Record r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("event_id", r.get("event_id"));
    m.put("occurred_at", r.get("occurred_at"));
    m.put("kind", r.get("kind"));
    m.put("actor", r.get("actor"));
    m.put("strategy_id", r.get("strategy_id"));
    m.put("workflow_id", r.get("workflow_id"));
    m.put("correlation_id", r.get("correlation_id"));
    m.put("subject", r.get("subject_json"));
    return Collections.unmodifiableMap(m);
  }
}
