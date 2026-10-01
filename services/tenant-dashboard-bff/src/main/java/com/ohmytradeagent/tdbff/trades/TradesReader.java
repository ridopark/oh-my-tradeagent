package com.ohmytradeagent.tdbff.trades;

// audit_log select COPIED FROM services/api-gateway/.../web/AuditController.java — keep in sync.
// Narrowed to the two FILL kinds a tenant cares about (EntryFilled + PartialExitFilled) and scoped
// to the tenant's whole strategy set (strategy_id IN (...)).
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Read-only view of a tenant's fills from the orchestrator's {@code audit_log}. */
@Component
public class TradesReader {

  private static final Logger log = LoggerFactory.getLogger(TradesReader.class);

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

    return withEntryBasis(
        tenantId,
        orchestratorDsl
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
            .toList());
  }

  /**
   * Weighted entry cost basis per entry signal, so an exit row can say what it MADE rather than
   * only what it sold at.
   *
   * <p>Weighted, not "the entry price": an entry can grow after its first fill (#738 books the rest
   * as {@code PositionEntryIncreased}, which carries its own {@code avg_fill_price}), and those
   * fills can be at different prices — a live prod_real SPY position is 32 contracts at a blended
   * 1.7425, not at any single fill's price. Taking the first fill alone would mis-state every exit
   * on a grown position.
   *
   * <p>Only fills that carried a price contribute to both the quantity and the cost, so the average
   * stays consistent rather than diluted by quantity it cannot value.
   */
  private Map<String, BigDecimal> entryBasisBySignal(String tenantId, Set<String> correlationIds) {
    if (correlationIds.isEmpty()) {
      return Map.of();
    }
    try {
      return orchestratorDsl
          .fetch(
              "SELECT correlation_id,"
                  + " SUM(COALESCE((subject->>'filled_qty')::bigint,"
                  + "              (subject->>'qty_added')::bigint)"
                  + "     * (subject->>'avg_fill_price')::numeric)"
                  + " / NULLIF(SUM(COALESCE((subject->>'filled_qty')::bigint,"
                  + "                       (subject->>'qty_added')::bigint)), 0) AS basis"
                  + " FROM audit_log"
                  + " WHERE tenant_id = ? AND correlation_id = ANY(?)"
                  + "   AND kind IN ('EntryFilled', 'PositionEntryIncreased')"
                  + "   AND subject->>'avg_fill_price' IS NOT NULL"
                  + " GROUP BY correlation_id",
              tenantId,
              correlationIds.toArray(new String[0]))
          .stream()
          .filter(r -> r.get(1, BigDecimal.class) != null)
          .collect(
              java.util.stream.Collectors.toMap(
                  r -> r.get(0, String.class), r -> r.get(1, BigDecimal.class)));
    } catch (RuntimeException e) {
      // Fail-soft, like every other enrichment on this page: no basis means the strip shows the
      // fill without a P&L, never a failed read.
      log.debug("entry-basis read failed tenant={}: {}", tenantId, e.toString());
      return Map.of();
    }
  }

  /** Attaches {@code entry_basis} to each row so an exit can be valued against what it cost. */
  private List<Map<String, Object>> withEntryBasis(
      String tenantId, List<Map<String, Object>> items) {
    Set<String> ids = new LinkedHashSet<>();
    for (Map<String, Object> m : items) {
      Object cid = m.get("correlation_id");
      if (cid instanceof String s && !s.isBlank()) {
        ids.add(s);
      }
    }
    Map<String, BigDecimal> basis = entryBasisBySignal(tenantId, ids);
    if (basis.isEmpty()) {
      return items;
    }
    List<Map<String, Object>> out = new ArrayList<>(items.size());
    for (Map<String, Object> m : items) {
      Map<String, Object> copy = new LinkedHashMap<>(m);
      copy.put("entry_basis", basis.get(String.valueOf(m.get("correlation_id"))));
      out.add(Collections.unmodifiableMap(copy));
    }
    return out;
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
