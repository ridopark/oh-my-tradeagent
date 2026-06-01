package com.ohmytradeagent.tdbff.platform;

import com.ohmytradeagent.tdbff.platform.TenantStrategyScanner.TenantStrategy;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves a tenant's full strategy set from the mounted tenants tree. Auth yields only a {@code
 * tenant_id}, but every read scopes on {@code (tenant_id, strategy_id)}; a tenant may own several
 * strategies, so the BFF aggregates across all of them (mirroring how {@code
 * VisibilityPortfolioSnapshot} widens its cap basis to the tenant's whole book). Reads the tree
 * fresh each call so an operator adding a strategy file does not require a restart.
 */
@Component
public class TenantStrategyResolver {

  private final Path tenantsDir;

  public TenantStrategyResolver(@Value("${bff.tenants-dir:tenants}") String tenantsDir) {
    this.tenantsDir = Path.of(tenantsDir);
  }

  /** Strategy ids owned by {@code tenantId}, in scan order. Empty if the tenant has none. */
  public List<String> strategyIdsForTenant(String tenantId) {
    Set<String> ids = new LinkedHashSet<>();
    for (TenantStrategy ts : TenantStrategyScanner.scan(tenantsDir)) {
      if (ts.tenantId().equals(tenantId)) {
        ids.add(ts.strategyId());
      }
    }
    return List.copyOf(ids);
  }
}
