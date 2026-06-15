package com.ohmytradeagent.exec.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * P6-c exec-INTERNAL HTTP response body for {@code POST /internal/broker-credentials}. Carries the
 * new row {@code version}, the {@code kek_version} the row's DEK was wrapped under, and the
 * verified {@code broker_account_id} — all NON-SECRET metadata returned by {@link
 * com.ohmytradeagent.exec.broker.alpaca.BrokerCredentialWriter#save} so the UI-P2-a api-gateway
 * caller can record a complete {@code SAVED} audit. It NEVER echoes any key or secret (MF-7); since
 * it holds no key material the default record {@code toString} is left intact.
 */
public record BrokerCredentialWriteResponse(
    @JsonProperty("version") long version,
    @JsonProperty("kek_version") int kekVersion,
    @JsonProperty("broker_account_id") String brokerAccountId) {}
