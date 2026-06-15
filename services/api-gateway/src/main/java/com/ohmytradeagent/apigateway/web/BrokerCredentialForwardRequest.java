package com.ohmytradeagent.apigateway.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * UI-P2-a api-gateway-INTERNAL request body for {@code POST /broker-credentials}. The dashboard
 * server POSTs the tenant-entered broker api-key/secret here; the gateway forwards it (minus {@code
 * correlation_id}) to exec's {@code POST /internal/broker-credentials}. The secret rides ONLY this
 * Java-to-Java HTTP body — it never reaches Temporal history, the DB unencrypted, or a log line
 * (MF-7).
 *
 * <p><b>NOT a contract type.</b> This record lives in {@code com.ohmytradeagent.apigateway.web},
 * deliberately outside {@code com.ohmytradeagent.contract}, so it can never be codegen'd or wired
 * into a Temporal {@code @ActivityInterface}/{@code @WorkflowInterface} input. {@link
 * BrokerCredentialForwardSecretGuardTest} mechanically enforces this.
 *
 * <p><b>{@code toString} is REDACTED (MF-7).</b> Spring MVC's message-converter logging renders the
 * deserialized {@code @RequestBody} via {@code toString} at DEBUG/TRACE; a record's default {@code
 * toString} would echo every component including the key/secret. Overriding it to show {@code ***}
 * for {@code apiKeyId}/{@code apiSecretKey} (while keeping the non-secret tenant / provider /
 * expected_version visible) is the structural defense.
 */
public record BrokerCredentialForwardRequest(
    @JsonProperty("tenant_id") String tenantId,
    @JsonProperty("provider") String provider,
    @JsonProperty("api_key_id") String apiKeyId,
    @JsonProperty("api_secret_key") String apiSecretKey,
    @JsonProperty("base_url") String baseUrl,
    @JsonProperty("ws_url") String wsUrl,
    @JsonProperty("declared_account_id") String declaredAccountId,
    @JsonProperty("expected_version") long expectedVersion,
    // WRITE_ONLY: accepted on the inbound dashboard POST, but NOT serialized when this record is
    // forwarded to exec — correlation_id is an api-gateway-only concern, so the same record can be
    // sent straight through without a second exec-shaped copy.
    @JsonProperty(value = "correlation_id", access = JsonProperty.Access.WRITE_ONLY)
        String correlationId) {

  @Override
  public String toString() {
    // NEVER render apiKeyId / apiSecretKey. Coarse, non-secret identifiers only.
    return "BrokerCredentialForwardRequest[tenantId="
        + tenantId
        + ", provider="
        + provider
        + ", apiKeyId=***, apiSecretKey=***, baseUrl="
        + baseUrl
        + ", wsUrl="
        + wsUrl
        + ", declaredAccountId="
        + declaredAccountId
        + ", expectedVersion="
        + expectedVersion
        + ", correlationId="
        + correlationId
        + "]";
  }
}
