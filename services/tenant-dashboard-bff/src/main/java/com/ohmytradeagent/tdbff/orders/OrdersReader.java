package com.ohmytradeagent.tdbff.orders;

// Column set COPIED FROM services/exec/.../journal/JooqOrderIntentJournal.java mapRow — keep in
// sync. The existing journal only exposes listOpenByTenantStrategy (RECORDED/SUBMITTED); this is a
// NEW all-states history select: WHERE tenant_id=? AND strategy_id IN (...) ORDER BY recorded_at
// DESC LIMIT ?.
import com.ohmytradeagent.tdbff.config.BrokerDataSourceRouter;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Read-only order history from the exec broker's {@code order_intent_journal}. A tenant's
 * strategies are grouped by {@code broker_target} (each broker_target → one exec DB); the history
 * is the union across the tenant's brokers, newest first.
 */
@Component
public class OrdersReader {

  static final int DEFAULT_LIMIT = 100;
  static final int MAX_LIMIT = 500;

  private final TenantStrategyResolver strategyResolver;
  private final DbStrategyConfigReader strategyRegistry;
  private final BrokerDataSourceRouter router;

  public OrdersReader(
      TenantStrategyResolver strategyResolver,
      DbStrategyConfigReader strategyRegistry,
      BrokerDataSourceRouter router) {
    this.strategyResolver = strategyResolver;
    this.strategyRegistry = strategyRegistry;
    this.router = router;
  }

  /**
   * Order-journal rows for the tenant across its strategies, newest first, capped at {@code limit}
   * (default 100, max 500). Strategies are bucketed by their {@code broker_target} so each exec DB
   * is queried once with the relevant {@code strategy_id} set.
   */
  public List<Map<String, Object>> orders(String tenantId, int limit) {
    int cappedLimit = Math.max(1, Math.min(MAX_LIMIT, limit <= 0 ? DEFAULT_LIMIT : limit));

    // Group the tenant's strategies by broker_target (one exec DB per target).
    Map<String, List<String>> strategiesByBroker = new LinkedHashMap<>();
    for (String strategyId : strategyResolver.strategyIdsForTenant(tenantId)) {
      String brokerTarget = strategyRegistry.brokerTarget(tenantId, strategyId);
      // brokerTarget reads fail-soft (null = unconfigured / missing config row). A null target must
      // NOT flow to router.dslFor below — that would throw BrokerNotConfiguredException and 404 the
      // whole tenant's order history. Omit the unconfigured strategy instead.
      if (brokerTarget == null) {
        continue;
      }
      strategiesByBroker.computeIfAbsent(brokerTarget, k -> new ArrayList<>()).add(strategyId);
    }

    List<Map<String, Object>> all = new ArrayList<>();
    for (Map.Entry<String, List<String>> e : strategiesByBroker.entrySet()) {
      DSLContext dsl = router.dslFor(e.getKey()); // 404 if broker_target not configured
      all.addAll(query(dsl, tenantId, e.getValue(), cappedLimit));
    }

    // Union across brokers, then re-sort newest-first and re-cap (each per-broker query already
    // capped, so the union is at most brokers × limit before this trim).
    all.sort(OrdersReader::byRecordedAtDesc);
    return all.size() > cappedLimit ? all.subList(0, cappedLimit) : all;
  }

  private static List<Map<String, Object>> query(
      DSLContext dsl, String tenantId, List<String> strategyIds, int limit) {
    if (strategyIds.isEmpty()) {
      return List.of();
    }
    return dsl
        .select(
            DSL.field("intent_key"),
            DSL.field("signal_id"),
            DSL.field("strategy_id"),
            DSL.field("broker_target"),
            DSL.field("option_symbol"),
            DSL.field("side"),
            DSL.field("qty"),
            DSL.field("limit_price"),
            DSL.field("state"),
            DSL.field("broker_order_id"),
            // Type the TIMESTAMPTZ columns so jOOQ returns OffsetDateTime (untyped DSL.field yields
            // java.sql.Timestamp, which the cross-broker re-sort in byRecordedAtDesc can't cast).
            DSL.field("recorded_at", OffsetDateTime.class),
            DSL.field("submitted_at", OffsetDateTime.class),
            DSL.field("filled_qty"),
            DSL.field("avg_fill_price"),
            DSL.field("filled_at", OffsetDateTime.class),
            DSL.field("last_error"))
        .from(DSL.table("order_intent_journal"))
        .where(DSL.field("tenant_id").eq(tenantId).and(DSL.field("strategy_id").in(strategyIds)))
        .orderBy(DSL.field("recorded_at").desc())
        .limit(limit)
        .fetch()
        .stream()
        .map(OrdersReader::row)
        .toList();
  }

  private static Map<String, Object> row(Record r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("intent_key", r.get("intent_key"));
    m.put("signal_id", r.get("signal_id"));
    m.put("strategy_id", r.get("strategy_id"));
    m.put("broker_target", r.get("broker_target"));
    m.put("option_symbol", r.get("option_symbol"));
    m.put("side", r.get("side"));
    m.put("qty", r.get("qty"));
    m.put("limit_price", r.get("limit_price"));
    m.put("state", r.get("state"));
    m.put("broker_order_id", r.get("broker_order_id"));
    m.put("recorded_at", r.get("recorded_at"));
    m.put("submitted_at", r.get("submitted_at"));
    m.put("filled_qty", r.get("filled_qty"));
    m.put("avg_fill_price", r.get("avg_fill_price"));
    m.put("filled_at", r.get("filled_at"));
    m.put("last_error", r.get("last_error"));
    return m;
  }

  private static int byRecordedAtDesc(Map<String, Object> a, Map<String, Object> b) {
    OffsetDateTime ra = (OffsetDateTime) a.get("recorded_at");
    OffsetDateTime rb = (OffsetDateTime) b.get("recorded_at");
    if (ra == null && rb == null) {
      return 0;
    }
    if (ra == null) {
      return 1;
    }
    if (rb == null) {
      return -1;
    }
    return rb.compareTo(ra);
  }
}
