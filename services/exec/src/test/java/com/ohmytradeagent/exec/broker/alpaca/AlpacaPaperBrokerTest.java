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
    // Alpaca's live endpoint expects qty / ratio_qty as JSON integers, not strings.
    assertThat(body.get("qty").isIntegralNumber()).isTrue();
    assertThat(body.get("qty").asLong()).isEqualTo(1L);
    // Alpaca's live endpoint requires limit_price as a JSON number — string form is sandbox-only.
    assertThat(body.get("limit_price").isNumber()).isTrue();
    assertThat(body.get("limit_price").decimalValue()).isEqualByComparingTo("2.30");
    assertThat(body.get("type").asText()).isEqualTo("limit");
    assertThat(body.get("time_in_force").asText()).isEqualTo("day");
    JsonNode legs = body.get("legs");
    assertThat(legs.isArray()).isTrue();
    assertThat(legs.size()).isEqualTo(1);
    assertThat(legs.get(0).get("symbol").asText()).isEqualTo("NVDA  260516C00140000");
    assertThat(legs.get(0).get("side").asText()).isEqualTo("buy");
    assertThat(legs.get(0).get("position_intent").asText()).isEqualTo("buy_to_open");
    assertThat(legs.get(0).get("ratio_qty").isIntegralNumber()).isTrue();
    assertThat(legs.get(0).get("ratio_qty").asLong()).isEqualTo(1L);
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
  void placeOrder_invalidContract_throwsInvalidContractErrorNonRetryable() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(400)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"code\":\"invalid_contract\",\"message\":\"unknown symbol\"}"));

    assertThatThrownBy(() -> broker.placeOrder(request("intent-A")))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("InvalidContractError");
              assertThat(f.isNonRetryable()).isTrue();
            });
  }

  @Test
  void getOrderStatus_unauthorized_throwsAuthErrorNonRetryable() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(401)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"forbidden.\"}"));

    assertThatThrownBy(() -> broker.getOrderStatus("alp-1"))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("AuthError");
              assertThat(f.isNonRetryable()).isTrue();
            });
  }

  @Test
  void placeOrder_omitsLimitPriceWhenNull() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"alp-12345\",\"client_order_id\":\"intent-A\",\"status\":\"accepted\"}"));

    PlaceOrderRequest req =
        new PlaceOrderRequest("intent-A", "NVDA  260516C00140000", "BUY", 1L, null);
    broker.placeOrder(req);

    RecordedRequest recorded = server.takeRequest();
    JsonNode body = mapper.readTree(recorded.getBody().readUtf8());
    assertThat(body.has("limit_price")).isFalse();
    // When limitPrice is null, the wire shape must be a market order — Alpaca rejects a
    // limit-type request without a limit_price field, and the resulting 400 doesn't match any
    // non-retryable mapping in mapError, which would loop the activity until the schedule lapses.
    assertThat(body.get("type").asText()).isEqualTo("market");
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
  void cancelOrder_401_throwsAuthError() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(401)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"invalid api key\"}"));

    assertThatThrownBy(() -> broker.cancelOrder("alp-12345"))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageContaining("AuthError");
  }

  @Test
  void placeOrder_throws_InvalidRequestError_on_422_validation() {
    // A non-duplicate 422 (e.g. unsupported time_in_force) must be non-retryable —
    // retrying a permanently-bad request shape only burns the schedule deadline.
    server.enqueue(
        new MockResponse()
            .setResponseCode(422)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"time_in_force not supported for option orders\"}"));

    PlaceOrderRequest req =
        new PlaceOrderRequest("intent-422", "NVDA  260516C00140000", "BUY", 1L, new BigDecimal("2.30"));

    assertThatThrownBy(() -> broker.placeOrder(req))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageContaining("InvalidRequestError");
  }

  @Test
  void cancelOrder_503_isRetryable() {
    // Transient 5xx must propagate as HttpStatusCodeException so Temporal retries the activity
    // instead of swallowing the cancel attempt. The OptionsBroker contract reserves CancelResponse
    // for 4xx semantic failures only.
    server.enqueue(
        new MockResponse()
            .setResponseCode(503)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"service unavailable\"}"));

    assertThatThrownBy(() -> broker.cancelOrder("alp-12345"))
        .isInstanceOf(org.springframework.web.client.HttpStatusCodeException.class);
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
