package com.ohmytradeagent.orchestrator.platform;

/**
 * Thrown by {@link TenantConfigWriter#update} when no {@code tenant_config} row exists for the
 * target tenant. Mirrors {@link YamlStrategyRegistry.StrategyNotFoundException} on the per-strategy
 * write path — the account cap is tenant-scoped, so its not-found is keyed on {@code tenant_id}
 * alone. The api-gateway maps it to a 404.
 */
public class TenantConfigNotFoundException extends RuntimeException {
  public TenantConfigNotFoundException(String message) {
    super(message);
  }
}
