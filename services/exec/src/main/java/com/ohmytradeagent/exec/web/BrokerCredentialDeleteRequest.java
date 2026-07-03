package com.ohmytradeagent.exec.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for the dark {@code DELETE /internal/broker-credentials} teardown route. Identifies
 * the credential row to remove by {@code (tenant_id, provider)} ONLY — it carries NO key material,
 * so the default record {@code toString} is left intact (mirrors {@link
 * BrokerCredentialWriteResponse}). Never a {@code contract/} type and never reachable from a
 * Temporal input.
 */
public record BrokerCredentialDeleteRequest(
    @JsonProperty("tenant_id") String tenantId, @JsonProperty("provider") String provider) {}
