package com.ohmytradeagent.apigateway.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * account-loss-cap-db (Phase 3) api-gateway-INTERNAL request body for {@code POST /tenant-config}.
 * The dashboard server POSTs the FULL desired account-cap state ({@code
 * account_daily_loss_threshold} + {@code account_daily_loss_pct}, either may be null) plus the
 * optimistic-concurrency {@code expected_version}; the controller asserts {@code tenant_id} equals
 * the validated {@code X-Tenant-Id}, then starts the {@code TenantConfigUpdateWorkflow} and maps
 * its coarse outcome to an HTTP status.
 *
 * <p>Carries NO secret — the record {@code toString} is safe and no redaction is needed.
 */
public record TenantConfigWriteRequest(
    @JsonProperty("tenant_id") String tenantId,
    @JsonProperty("account_daily_loss_threshold") BigDecimal accountDailyLossThreshold,
    @JsonProperty("account_daily_loss_pct") BigDecimal accountDailyLossPct,
    @JsonProperty("expected_version") long expectedVersion,
    @JsonProperty("correlation_id") String correlationId) {}
