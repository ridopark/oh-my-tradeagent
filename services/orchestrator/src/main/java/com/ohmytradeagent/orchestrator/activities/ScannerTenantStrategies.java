package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.orchestrator.bootstrap.TenantStrategyScanner;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
import java.nio.file.Path;
import java.util.List;

/**
 * Issue #323 part (a): {@link TenantStrategies} backed by {@link TenantStrategyScanner}. Scans the
 * {@code tenants/} tree on each call and returns the strategy ids under {@code
 * tenants/<tenantId>/strategies/}. Reads fresh each call so an operator adding a strategy file does
 * not require a restart for the cap basis to widen.
 *
 * <p><b>Fail-CLOSED (#325).</b> The scanner throws {@link IllegalStateException} on an I/O error
 * (unreadable tenants tree); that throw is allowed to propagate so the {@code openPositions} call
 * fails closed rather than building a query off an empty strategy set (which would loosen the cap).
 */
public final class ScannerTenantStrategies implements TenantStrategies {

  private final Path tenantsDir;

  public ScannerTenantStrategies(Path tenantsDir) {
    this.tenantsDir = tenantsDir;
  }

  @Override
  public List<String> strategyIdsForTenant(String tenantId) {
    return TenantStrategyScanner.scan(tenantsDir).stream()
        .filter(ts -> ts.tenantId().equals(tenantId))
        .map(TenantStrategy::strategyId)
        .toList();
  }
}
