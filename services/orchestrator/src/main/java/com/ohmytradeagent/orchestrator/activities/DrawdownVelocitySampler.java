package com.ohmytradeagent.orchestrator.activities;

import java.math.BigDecimal;

/**
 * Issue #6: returns the trailing-minute MTM loss rate (absolute dollars per minute) for {@code
 * (tenant, strategy)}. Positive values are losses; the gate rejects when the returned value is
 * {@code >= drawdown_velocity_threshold}.
 *
 * <p>Stub returns {@code BigDecimal.ZERO} so the gate is effectively no-op until a real MTM source
 * lands. The gate is also opt-in via {@code drawdown_velocity_threshold == null}, so a strategy
 * never gets surprise rejections from this sampler.
 */
@FunctionalInterface
public interface DrawdownVelocitySampler {

  BigDecimal sampleLossRatePerMinute(String tenantId, String strategyId);
}
