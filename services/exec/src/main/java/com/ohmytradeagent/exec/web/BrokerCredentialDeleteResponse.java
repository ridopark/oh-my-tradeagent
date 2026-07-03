package com.ohmytradeagent.exec.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body for {@code DELETE /internal/broker-credentials}: the number of {@code
 * broker_credentials} rows removed (0 when the row was already absent — an idempotent success). Non
 * secret; holds no key material.
 */
public record BrokerCredentialDeleteResponse(@JsonProperty("deleted") int deleted) {}
