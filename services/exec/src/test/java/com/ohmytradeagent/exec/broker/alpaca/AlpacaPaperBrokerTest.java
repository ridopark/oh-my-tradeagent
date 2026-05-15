package com.ohmytradeagent.exec.broker.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.exec.broker.BrokerOrderStatus;
import com.ohmytradeagent.exec.broker.CancelResponse;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.math.BigDecimal;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Drives {@link AlpacaPaperBroker} against an in-process {@link MockWebServer}. Covers the wire
 * shape (POST /v2/orders body), happy paths for each verb, Alpaca's 422 duplicate flow, and the
 * specific error-mapping branches we promise the workflow ({@code AuthError}, {@code
 * InsufficientFundsError}).
 */
class AlpacaPaperBrokerTest {

  private MockWebServer server;
  private AlpacaPaperBroker broker;
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void start() throws IOException {
    server = new MockWebServer();
    server.start();
    RestClient client =
        RestClient.builder()
            .baseUrl(server.url("/").toString().replaceAll("/$", ""))
            .defaultHeader("APCA-API-KEY-ID", "key-id-for-test")
            .defaultHeader("APCA-API-SECRET-KEY", "key-secret-for-test")
            .defaultHeader("Accept", "application/json")
            .build();
    broker = new AlpacaPaperBroker(client, mapper);
  }

  @AfterEach
  void stop() throws IOException {
    server.shutdown();
  }

  @Test
  void placeOrder_happyPath_extractsBrokerOrderIdAndAuthHeaders() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"alp-12345\",\"client_order_id\":\"intent-A\",\"status\":\"accepted\"}"));

    PlaceOrderResponse r = broker.placeOrder(request("intent-A"));

    assertThat(r.brokerOrderId()).isEqualTo("alp-12345");
    assertThat(r.alreadyExisted()).isFalse();

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("POST");
    assertThat(req.getPath()).isEqualTo("/v2/orders");
    assertThat(req.getHeader("APCA-API-KEY-ID")).isEqualTo("key-id-for-test");
    assertThat(req.getHeader("APCA-API-SECRET-KEY")).isEqualTo("key-secret-for-test");

    JsonNode body = mapper.readTree(req.getBody().readUtf8());
    assertThat(body.get("client_order_id").asText()).isEqualTo("intent-A");
    assertThat(body.get("order_class").asText()).isEqualTo("mleg");
    assertThat(body.get("qty").asText()).isEqualTo("1");
    assertThat(body.get("limit_price").asText()).isEqualTo("2.30");
    assertThat(body.get("type").asText()).isEqualTo("limit");
    assertThat(body.get("time_in_force").asText()).isEqualTo("day");
    JsonNode legs = body.get("legs");
    assertThat(legs.isArray()).isTrue();
    assertThat(legs.size()).isEqualTo(1);
    assertThat(legs.get(0).get("symbol").asText()).isEqualTo("NVDA  260516C00140000");
    assertThat(legs.get(0).get("side").asText()).isEqualTo("buy");
    assertThat(legs.get(0).get("position_intent").asText()).isEqualTo("buy_to_open");
    assertThat(legs.get(0).get("ratio_qty").asText()).isEqualTo("1");
  }

  @Test
  void placeOrder_duplicateClientOrderId_returnsExistingIdAndAlreadyExistedTrue() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(422)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"message\":\"client_order_id must be unique\",\"existing_order_id\":\"alp-prior-9\"}"));

    PlaceOrderResponse r = broker.placeOrder(request("intent-A"));

    assertThat(r.brokerOrderId()).isEqualTo("alp-prior-9");
    assertThat(r.alreadyExisted()).isTrue();
  }

  @Test
  void placeOrder_unauthorized_throwsAuthErrorNonRetryable() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(401)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"forbidden.\"}"));

    assertThatThrownBy(() -> broker.placeOrder(request("intent-A")))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("AuthError");
              assertThat(f.isNonRetryable()).isTrue();
            });
  }

  @Test
  void placeOrder_insufficientBuyingPower_throwsInsufficientFundsErrorNonRetryable() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(403)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"code\":\"insufficient_buying_power\",\"message\":\"insufficient buying power\"}"));

    assertThatThrownBy(() -> broker.placeOrder(request("intent-A")))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("InsufficientFundsError");
              assertThat(f.isNonRetryable()).isTrue();
            });
  }

  @Test
  void cancelOrder_204_returnsOk() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(204));

    CancelResponse c = broker.cancelOrder("alp-12345");

    assertThat(c.cancelled()).isTrue();
    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("DELETE");
    assertThat(req.getPath()).isEqualTo("/v2/orders/alp-12345");
  }

  @Test
  void cancelOrder_422OnFilled_returnsSoftFailureWithReason() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(422)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"order cannot be canceled\"}"));

    CancelResponse c = broker.cancelOrder("alp-12345");

    assertThat(c.cancelled()).isFalse();
    assertThat(c.brokerReason()).contains("alpaca status 422");
    assertThat(c.brokerReason()).contains("order cannot be canceled");
  }

  @Test
  void getOrderStatus_mapsAllAlpacaStatusStrings() {
    enqueueStatus("new");
    enqueueStatus("accepted");
    enqueueStatus("partially_filled");
    enqueueStatus("filled");
    enqueueStatus("canceled");
    enqueueStatus("expired");
    enqueueStatus("rejected");
    enqueueStatus("some_future_status");

    assertThat(broker.getOrderStatus("alp-1")).isEqualTo(BrokerOrderStatus.OPEN);
    assertThat(broker.getOrderStatus("alp-1")).isEqualTo(BrokerOrderStatus.OPEN);
    assertThat(broker.getOrderStatus("alp-1")).isEqualTo(BrokerOrderStatus.OPEN);
    assertThat(broker.getOrderStatus("alp-1")).isEqualTo(BrokerOrderStatus.FILLED);
    assertThat(broker.getOrderStatus("alp-1")).isEqualTo(BrokerOrderStatus.CANCELLED);
    assertThat(broker.getOrderStatus("alp-1")).isEqualTo(BrokerOrderStatus.CANCELLED);
    assertThat(broker.getOrderStatus("alp-1")).isEqualTo(BrokerOrderStatus.REJECTED);
    assertThat(broker.getOrderStatus("alp-1")).isEqualTo(BrokerOrderStatus.UNKNOWN);
  }

  @Test
  void getOrderStatus_404_returnsUnknown() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(404)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"order not found\"}"));

    assertThat(broker.getOrderStatus("alp-ghost")).isEqualTo(BrokerOrderStatus.UNKNOWN);
  }

  private void enqueueStatus(String alpacaStatus) {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"alp-1\",\"client_order_id\":\"x\",\"status\":\""
                    + alpacaStatus
                    + "\"}"));
  }

  private static PlaceOrderRequest request(String clientOrderId) {
    return new PlaceOrderRequest(
        clientOrderId, "NVDA  260516C00140000", "BUY", 1L, new BigDecimal("2.30"));
  }
}
