package com.ohmytradeagent.orchestrator.activities;

import java.util.List;

/**
 * Issue #323 part (a): resolves the set of strategy ids that belong to a tenant, used by {@link
 * VisibilityPortfolioSnapshot} to widen the {@code openPositions} Visibility query from a single
 * {@code (tenant, strategy)} to <b>all of the tenant's strategies</b> on the same {@code
 * broker_target} — so the {@code notional_cap_pct_of_equity}, {@code same_underlying_count}, and
 * {@code sector_concentration_cap} gates observe the tenant's whole running book.
 *
 * <p>Cross-tenant isolation is preserved structurally: only the <i>current</i> tenant's strategies
 * are ever returned, so another tenant's PositionWorkflows never enter the snapshot. The
 * single-tenant single-strategy deployment yields a one-element list, which collapses the {@code
 * TenantStrategy IN (...)} clause to a single value — the same result set as the pre-#323 {@code
 * TenantStrategy='...'} equality filter (inertness).
 *
 * <p><b>Fail-CLOSED contract (#325).</b> A resolver backed by the tenants tree must let an I/O
 * error (unreadable tenants dir) <b>propagate</b>, not swallow it into an empty list: an empty
 * strategy set would build a query that matches nothing → {@code sum_open_notional=0} → loosens the
 * cap → fail-OPEN. The throw is what keeps the gate fail-closed at the activity boundary.
 */
@FunctionalInterface
public interface TenantStrategies {

  /**
   * The strategy ids owned by {@code tenantId}. Never returns {@code null}; throws (does not return
   * empty) if the underlying tenants tree cannot be read.
   */
  List<String> strategyIdsForTenant(String tenantId);
}
