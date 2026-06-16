package com.ohmytradeagent.apigateway.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ohmytradeagent.contract.StrategyConfig;

/**
 * UI-P3-b api-gateway-INTERNAL request body for {@code POST /strategy-config}. The dashboard server
 * POSTs the proposed full {@link StrategyConfig} blob plus the optimistic-concurrency {@code
 * expected_version}; the controller asserts {@code tenant_id} equals the validated {@code
 * X-Tenant-Id}, then starts the {@code StrategyConfigUpdateWorkflow} and maps its coarse outcome to
 * an HTTP status.
 *
 * <p>Carries NO secret — {@link StrategyConfig} has no credential-bearing fields — so (unlike
 * {@code BrokerCredentialForwardRequest}) the default record {@code toString} is safe and no
 * redaction is needed.
 */
public record StrategyConfigWriteRequest(
    @JsonProperty("tenant_id") String tenantId,
    @JsonProperty("strategy_id") String strategyId,
    @JsonProperty("config") StrategyConfig config,
    @JsonProperty("expected_version") long expectedVersion,
    @JsonProperty("correlation_id") String correlationId) {}
