package com.ohmytradeagent.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

/**
 * UI-P2-a: the exec client is the secret-bearing hop, so it mirrors {@code ServiceTokenFilter}'s
 * prod posture — under the {@code prod} profile a blank exec admin token must refuse to boot rather
 * than forward credentials under an empty bearer. Non-prod tolerates a blank token (local dev).
 */
class ExecClientConfigTest {

  private final ExecClientConfig config = new ExecClientConfig();

  @Test
  void prodProfile_blankExecToken_refusesToBoot() {
    MockEnvironment prod = new MockEnvironment();
    prod.setActiveProfiles("prod");
    assertThatThrownBy(
            () -> config.execRestClient(RestClient.builder(), prod, "http://exec:8080", "  "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exec.admin.service-token");
  }

  @Test
  void prodProfile_realExecToken_boots() {
    MockEnvironment prod = new MockEnvironment();
    prod.setActiveProfiles("prod");
    RestClient client =
        config.execRestClient(
            RestClient.builder(), prod, "http://exec:8080", "a-real-exec-admin-secret");
    assertThat(client).isNotNull();
  }

  @Test
  void nonProdProfile_blankExecToken_bootsForLocalDev() {
    MockEnvironment dev = new MockEnvironment();
    RestClient client = config.execRestClient(RestClient.builder(), dev, "http://exec:8080", "");
    assertThat(client).isNotNull();
  }
}
