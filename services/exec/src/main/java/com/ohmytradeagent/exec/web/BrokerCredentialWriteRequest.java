package com.ohmytradeagent.exec.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * P6-c exec-INTERNAL HTTP request body for {@code POST /internal/broker-credentials}. Carries the
 * tenant-entered broker api-key/secret in transit between two Java services (api-gateway → exec)
 * ONLY.
 *
 * <p><b>SECURITY (MF-7) — this record MUST NOT become a {@code contract/} type and MUST NOT be
 * reachable from any Temporal {@code @ActivityInterface}/{@code @WorkflowInterface} input.</b>
 * Temporal persists activity/workflow inputs in its durable history, so a secret on a Temporal type
 * is a hard MF-7 violation. The secret travels via direct authenticated HTTP and never touches
 * Temporal. {@link BrokerCredentialSecretGuardTest} mechanically enforces that the {@code apiKeyId}
 * / {@code apiSecretKey} field names never appear under {@code contract/schemas/} and that this
 * record lives outside the {@code com.ohmytradeagent.contract} package.
 *
 * <p><b>{@code toString} is REDACTED.</b> A record's default {@code toString} echoes every
 * component — including the api-key/secret — and Spring MVC's message-converter logging renders the
 * deserialized body via {@code toString} at DEBUG/TRACE. Overriding it to redact the secret fields
 * is the structural defense: even if a framework or a stray {@code log.debug("{}", body)} logs the
 * object, the secret never reaches a log line (MF-7). The controller and filter additionally never
 * log the body at all (asserted by {@link BrokerCredentialAdminControllerTest}).
 */
public record BrokerCredentialWriteRequest(
    @JsonProperty("tenant_id") String tenantId,
    @JsonProperty("provider") String provider,
    @JsonProperty("api_key_id") String apiKeyId,
    @JsonProperty("api_secret_key") String apiSecretKey,
    @JsonProperty("base_url") String baseUrl,
    @JsonProperty("ws_url") String wsUrl,
    @JsonProperty("declared_account_id") String declaredAccountId,
    @JsonProperty("expected_version") long expectedVersion) {

  @Override
  public String toString() {
    // NEVER render apiKeyId / apiSecretKey. Coarse identifiers only.
    return "BrokerCredentialWriteRequest[tenantId="
        + tenantId
        + ", provider="
        + provider
        + ", apiKeyId=<redacted>, apiSecretKey=<redacted>, baseUrl="
        + baseUrl
        + ", wsUrl="
        + wsUrl
        + ", declaredAccountId="
        + declaredAccountId
        + ", expectedVersion="
        + expectedVersion
        + "]";
  }
}
