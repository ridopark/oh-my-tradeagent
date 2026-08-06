package com.ohmytradeagent.apigateway.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ohmytradeagent.contract.StrategyConfig;
import java.math.BigDecimal;

/**
 * Phase I-1b api-gateway-INTERNAL request body for the operator create-tenant route {@code POST
 * /admin/tenants/{tenant}/strategies/{strategy}}. The dashboard server POSTs the proposed full
 * {@link StrategyConfig} blob; the (tenant, strategy) come from the PATH (operator-scoped — the
 * operator does not own the new tenant, so there is no X-Tenant-Id to bind), and the operator id
 * comes from the X-Operator-Id header. {@code StrategyConfigWriter.create} rejects a config whose
 * own tenant_id/strategy_id do not match the path target.
 *
 * <p>Carries NO secret — {@link StrategyConfig} has no credential-bearing fields (broker keys flow
 * through the separate credential write path) — so the default record {@code toString} is safe.
 *
 * <p>PLAN-2026-08-05-direct-live-tenant-onboarding: {@code account_daily_loss_pct} is the OPTIONAL
 * operator-supplied account-level daily-loss cap (a fraction of start-of-day equity). It is
 * forwarded verbatim onto the started {@link
 * com.ohmytradeagent.contract.StrategyConfigCreateRequest}; the orchestrator writer is the sole
 * authority — a LIVE create with no pre-existing cap and a null value here is still rejected, and a
 * paper create ignores it. No gateway-side validation.
 */
public record TenantCreateRequest(
    @JsonProperty("config") StrategyConfig config,
    @JsonProperty("correlation_id") String correlationId,
    @JsonProperty("account_daily_loss_pct") BigDecimal accountDailyLossPct) {}
