package com.ohmytradeagent.tdbff.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The BFF reads the broker fill-listener status only via api-gateway, with the service bearer. */
class FillListenerStatusClientTest {

  private static final String URL =
      "http://api-gateway:8082/internal/live/fill-listener-status?tenant=acme";

  private MockRestServiceServer gateway;
  private FillListenerStatusClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://api-gateway:8082");
    gateway = MockRestServiceServer.bindTo(builder).build();
    client = new FillListenerStatusClient(builder.build(), "svc-token");
  }

  @Test
  void ok_passesBodyThrough_withBearerAndTenant() {
    gateway
        .expect(requestTo(URL))
        .andExpect(header("Authorization", "Bearer svc-token"))
        .andRespond(
            withSuccess(
                "{\"enabled\":true,\"broker_target\":\"alpaca-live\",\"pod_tenant_count\":1,"
                    + "\"tenants\":[]}",
                MediaType.APPLICATION_JSON));

    assertThat(client.status("acme"))
        .containsEntry("enabled", true)
        .containsEntry("broker_target", "alpaca-live");
    gateway.verify();
  }

  @Test
  void non2xx_isUnknown_withGatewayReason() {
    gateway
        .expect(requestTo(URL))
        .andRespond(
            withStatus(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":\"unknown\",\"reason\":\"exec status read failed\"}"));

    assertThat(client.status("acme"))
        .containsEntry("status", "unknown")
        .containsEntry("reason", "api-gateway: exec status read failed");
  }

  @Test
  void unauthorized_isUnknown_withHttpStatusReason() {
    gateway
        .expect(requestTo(URL))
        .andRespond(
            withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"unauthorized\"}"));

    assertThat(client.status("acme"))
        .isEqualTo(Map.of("status", "unknown", "reason", "api-gateway HTTP 401"));
  }

  @Test
  void blankToken_isUnknown_withoutCalling() {
    FillListenerStatusClient noToken =
        new FillListenerStatusClient(RestClient.builder().baseUrl("http://unused").build(), "");
    assertThat(noToken.status("acme"))
        .containsEntry("status", "unknown")
        .containsEntry("reason", "api-gateway service token not configured");
  }

  @Test
  void unreachable_throws_forThePartFallback() {
    FillListenerStatusClient down = new FillListenerStatusClient("http://127.0.0.1:1", "t");
    assertThatThrownBy(() -> down.status("acme")).isInstanceOf(RuntimeException.class);
  }
}
