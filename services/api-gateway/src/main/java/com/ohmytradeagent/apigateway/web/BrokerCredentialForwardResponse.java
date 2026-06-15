package com.ohmytradeagent.apigateway.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Deserialization target for exec's {@code POST /internal/broker-credentials} success body {@code
 * {version, kek_version, broker_account_id}}. All three are NON-SECRET metadata; the gateway
 * returns only {@code version} to the dashboard and feeds {@code kek_version}/{@code
 * broker_account_id} into the SAVED audit.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BrokerCredentialForwardResponse(
    @JsonProperty("version") long version,
    @JsonProperty("kek_version") int kekVersion,
    @JsonProperty("broker_account_id") String brokerAccountId) {}
