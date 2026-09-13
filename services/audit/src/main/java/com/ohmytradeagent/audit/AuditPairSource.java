package com.ohmytradeagent.audit;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Discovers which (tenant, strategy) pairs actually have audit activity in a window.
 *
 * <p>Deliberately separate from {@link AuditEventSource} rather than another method on it: that
 * interface is a single-method read port and several tests drive it as a lambda. Segregating the
 * discovery concern keeps both simple.
 *
 * <p>Why discovery at all: the CronJob used to hardcode {@code TENANT=dev STRATEGY=copytrade-v1},
 * which has zero events — so it scored 100% on an empty set every night while five real tenants
 * went unchecked (#853). A hardcoded pair list is the bug; enumerating from the data means a new
 * tenant is covered the day it starts trading, with nothing to remember to update. Same reasoning
 * as {@code TenantReconcileLoop} enumerating from the DB rather than a mounted file.
 */
public interface AuditPairSource {

  /** A (tenant, strategy) partition of {@code audit_log}. */
  record TenantStrategy(String tenantId, String strategyId) {}

  /**
   * Every distinct (tenant_id, strategy_id) with at least one event in {@code [from, to)}, ordered
   * deterministically so the CronJob log reads the same way every night.
   */
  List<TenantStrategy> pairsInWindow(OffsetDateTime fromInclusive, OffsetDateTime toExclusive);
}
