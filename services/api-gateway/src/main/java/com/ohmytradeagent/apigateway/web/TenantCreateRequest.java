package com.ohmytradeagent.apigateway.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ohmytradeagent.contract.StrategyConfig;

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
 */
public record TenantCreateRequest(
    @JsonProperty("config") StrategyConfig config,
    @JsonProperty("correlation_id") String correlationId) {}
