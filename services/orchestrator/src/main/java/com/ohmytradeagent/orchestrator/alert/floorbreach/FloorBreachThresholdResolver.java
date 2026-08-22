package com.ohmytradeagent.orchestrator.alert.floorbreach;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Issue #779: resolves the per-strategy floor-breach alert threshold {@code floor_breach_alert_pct}
 * from the {@code strategy_config} DB row, with the {@code TenantWebhookResolver} pattern: a 30s
 * per-(tenant, strategy) cache, best-effort, never throws — any error / absent row / absent field /
 * out-of-range value falls back to {@link FloorBreachEvaluator#DEFAULT_THRESHOLD} (0.50). SAFE
 * field class, freely editable on /config; a dashboard edit takes effect within one cache TTL.
 */
@Component
public class FloorBreachThresholdResolver {

  private static final Logger log = LoggerFactory.getLogger(FloorBreachThresholdResolver.class);
  private static final Duration CACHE_TTL = Duration.ofSeconds(30);

  /** May be {@code null} in test/boot envs without a DataSource — handled fail-soft. */
  private final DSLContext dsl;

  private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

  @Autowired
  public FloorBreachThresholdResolver(@Autowired(required = false) DSLContext dsl) {
    this.dsl = dsl;
  }

  /** The threshold for {@code (tenantId, strategyId)}; never null, never throws. */
  public BigDecimal threshold(String tenantId, String strategyId) {
    if (dsl == null || tenantId == null || strategyId == null) {
      return FloorBreachEvaluator.DEFAULT_THRESHOLD;
    }
    String key = tenantId + "|" + strategyId;
    long now = System.currentTimeMillis();
    CacheEntry cached = cache.get(key);
    if (cached != null && now - cached.fetchedAtMillis() < CACHE_TTL.toMillis()) {
      return cached.threshold();
    }
    BigDecimal resolved = query(tenantId, strategyId);
    cache.put(key, new CacheEntry(resolved, now));
    return resolved;
  }

  private BigDecimal query(String tenantId, String strategyId) {
    try {
      Record row =
          dsl.fetchOne(
              "SELECT config->>'floor_breach_alert_pct' AS pct "
                  + "FROM strategy_config WHERE tenant_id = ? AND strategy_id = ?",
              tenantId,
              strategyId);
      if (row == null) {
        return FloorBreachEvaluator.DEFAULT_THRESHOLD;
      }
      String raw = row.get("pct", String.class);
      if (raw == null || raw.isBlank()) {
        return FloorBreachEvaluator.DEFAULT_THRESHOLD;
      }
      BigDecimal value = new BigDecimal(raw.trim());
      if (value.signum() <= 0 || value.compareTo(BigDecimal.ONE) >= 0) {
        // Out of the schema's (0, 0.95] range — an unusable threshold falls back to the default.
        return FloorBreachEvaluator.DEFAULT_THRESHOLD;
      }
      return value;
    } catch (RuntimeException e) {
      log.warn(
          "floor-breach threshold lookup failed tenant={} strategy={} (using default)",
          tenantId,
          strategyId,
          e);
      return FloorBreachEvaluator.DEFAULT_THRESHOLD;
    }
  }

  private record CacheEntry(BigDecimal threshold, long fetchedAtMillis) {}
}
