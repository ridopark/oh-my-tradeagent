package com.ohmytradeagent.apigateway.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A1 api-gateway-INTERNAL request body for the operator enable route {@code POST
 * /admin/tenants/{tenant}/strategies/{strategy}/enable}. The (tenant, strategy) come from the PATH
 * and the operator from {@code X-Operator-Id}; the only body field is an optional {@code
 * correlation_id} for the underlying {@code StrategyConfigUpdateWorkflow} dedupe. The body itself
 * is OPTIONAL (a bare enable click sends none) — the controller generates a correlation id when
 * absent.
 *
 * <p>Carries NO secret and NO config — the arming config is read server-side from the stored row,
 * so the operator cannot smuggle a risk-increasing edit through the enable click.
 */
public record StrategyEnableRequest(@JsonProperty("correlation_id") String correlationId) {}
