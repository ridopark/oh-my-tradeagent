package com.ohmytradeagent.orchestrator.platform;

/**
 * A {@code (tenantId, strategyId)} pair — the unit of tenant enumeration across the orchestrator.
 *
 * <p>Lives in {@code platform} (not {@code bootstrap}) so {@link StrategyRegistry#list()} can
 * return it without inverting the {@code platform → bootstrap} dependency direction. {@code
 * bootstrap.TenantStrategyScanner} and the boot-path bootstrappers consume this same record.
 */
public record TenantStrategy(String tenantId, String strategyId) {}
