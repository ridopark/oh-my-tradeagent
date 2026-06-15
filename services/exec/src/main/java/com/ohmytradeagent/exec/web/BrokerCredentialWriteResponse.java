package com.ohmytradeagent.exec.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * P6-c exec-INTERNAL HTTP response body for {@code POST /internal/broker-credentials}. Carries ONLY
 * the new row {@code version} returned by {@link
 * com.ohmytradeagent.exec.broker.alpaca.BrokerCredentialWriter#save}. It NEVER echoes any key or
 * secret (MF-7).
 */
public record BrokerCredentialWriteResponse(@JsonProperty("version") long version) {}
