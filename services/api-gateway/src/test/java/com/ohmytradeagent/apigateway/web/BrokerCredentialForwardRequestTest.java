package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * MF-7: the forward request's {@code toString} (what Spring MVC renders when it DEBUG/TRACE-logs
 * the {@code @RequestBody}) must NEVER echo the api-key/secret, while keeping the non-secret tenant
 * / provider / version visible for triage.
 */
class BrokerCredentialForwardRequestTest {

  private static final String API_KEY = "AKMY_SECRET_KEY_ID_12345";
  private static final String API_SECRET = "ssshhh-this-is-the-broker-secret";

  @Test
  void toStringRedactsKeyAndSecretButKeepsCoarseIdentifiers() {
    BrokerCredentialForwardRequest req =
        new BrokerCredentialForwardRequest(
            "acme",
            "alpaca",
            API_KEY,
            API_SECRET,
            "https://paper-api.alpaca.markets",
            "wss://paper-api.alpaca.markets/stream",
            "acct-1",
            0L,
            "corr-123");

    String rendered = req.toString();

    assertThat(rendered).doesNotContain(API_KEY);
    assertThat(rendered).doesNotContain(API_SECRET);
    assertThat(rendered).contains("***");
    assertThat(rendered).contains("tenantId=acme");
    assertThat(rendered).contains("provider=alpaca");
    assertThat(rendered).contains("expectedVersion=0");
  }
}
