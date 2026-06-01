package com.ohmytradeagent.exec.broker.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.PreTradeCheckRequest;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import com.ohmytradeagent.exec.broker.BrokerFillDetail;
import com.ohmytradeagent.exec.broker.BrokerOrderStatus;
import com.ohmytradeagent.exec.broker.CancelResponse;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpStatusCodeException;
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
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

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
    broker = new AlpacaPaperBroker(client, mapper, meterRegistry);
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
    // Alpaca's asset DB stores option symbols UNPADDED. The OptionsBroker contract carries
    // the canonical 21-char OSI form ("NVDA  260516C00140000"), the adapter strips root
    // padding before sending. Reverse: a padded symbol on the wire would be `not found`.
    assertThat(body.get("symbol").asText()).isEqualTo("NVDA260516C00140000");
    // Alpaca's live endpoint expects qty as a JSON integer, not a string.
    assertThat(body.get("qty").isIntegralNumber()).isTrue();
    assertThat(body.get("qty").asLong()).isEqualTo(1L);
    assertThat(body.get("side").asText()).isEqualTo("buy");
    assertThat(body.get("position_intent").asText()).isEqualTo("buy_to_open");
    assertThat(body.get("type").asText()).isEqualTo("limit");
    assertThat(body.get("time_in_force").asText()).isEqualTo("day");
    // Alpaca's live endpoint requires limit_price as a JSON number — string form is sandbox-only.
    assertThat(body.get("limit_price").isNumber()).isTrue();
    assertThat(body.get("limit_price").decimalValue()).isEqualByComparingTo("2.30");
    // Single-leg orders must NOT carry order_class=mleg or a legs[] array — Alpaca's mleg
    // endpoint rejects anything with fewer than 2 legs.
    assertThat(body.has("order_class")).isFalse();
    assertThat(body.has("legs")).isFalse();
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
  void cancelOrder_brokerReturns422AlreadyFilled_classifiedAsAlreadyFilled() {
    // Issue #165: Alpaca returns 422 with code=42210000 (and a message containing the
    // substring `already in "filled"`) when a cancel races a fill. The OptionsBroker
    // contract must classify this as ALREADY_FILLED so the activity reconciles the
    // journal to FILLED via getFillDetail instead of recording a generic cancel-failed
    // last_error (which orphans the position downstream).
    server.enqueue(
        new MockResponse()
            .setResponseCode(422)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"code\":42210000,\"message\":\"order is already in \\\"filled\\\" state\"}"));

    CancelResponse c = broker.cancelOrder("alp-12345");

    assertThat(c.outcome()).isEqualTo(CancelResponse.Outcome.ALREADY_FILLED);
    assertThat(c.cancelled()).isFalse();
    assertThat(c.brokerReason()).containsIgnoringCase("already in");
    assertThat(c.brokerReason()).contains("alpaca status 422");
  }

  @Test
  void cancelOrder_brokerReturns422OtherCode_classifiedAsFailed() {
    // Regression guard: a 422 carrying a different Alpaca code (or no fill-race
    // sentinel substring) must remain a FAILED cancel — we only promote the
    // specific cancel-on-filled sentinel to ALREADY_FILLED.
    server.enqueue(
        new MockResponse()
            .setResponseCode(422)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"code\":42210001,\"message\":\"order cannot be canceled\"}"));

    CancelResponse c = broker.cancelOrder("alp-12345");

    assertThat(c.outcome()).isEqualTo(CancelResponse.Outcome.FAILED);
    assertThat(c.cancelled()).isFalse();
    assertThat(c.brokerReason()).contains("alpaca status 422");
  }

  @Test
  void getFillDetail_brokerReturnsFilledOrder_returnsParsedDetail() throws Exception {
    // Issue #165: when cancel races a fill, the activity calls getFillDetail to
    // capture broker-confirmed filled_qty / avg_fill_price / filled_at and reconcile
    // the journal row to FILLED.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"alp-12345\",\"client_order_id\":\"intent-A\",\"status\":\"filled\","
                    + "\"filled_qty\":\"5\",\"filled_avg_price\":\"0.84\","
                    + "\"filled_at\":\"2026-05-19T17:08:11Z\"}"));

    BrokerFillDetail detail = broker.getFillDetail("alp-12345");

    assertThat(detail.filledQty()).isEqualTo(5L);
    assertThat(detail.avgFillPrice()).isEqualByComparingTo(new BigDecimal("0.84"));
    assertThat(detail.filledAt()).isEqualTo(OffsetDateTime.parse("2026-05-19T17:08:11Z"));

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("GET");
    assertThat(req.getPath()).isEqualTo("/v2/orders/alp-12345");
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
        new PlaceOrderRequest(
            "intent-422", "NVDA  260516C00140000", "BUY", 1L, new BigDecimal("2.30"));

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
        .isInstanceOf(HttpStatusCodeException.class);
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

  @Test
  void listOpenPositions_filtersOptionsAndParsesQty() throws Exception {
    // Issue #165 Phase 3: /v2/positions returns a flat array mixing equity + option holdings.
    // Filter to asset_class="us_option", drop short positions (v0 BrokerPosition only models
    // LONG), and parse qty/avg_entry_price from the string form Alpaca emits.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "[{\"symbol\":\"AAPL\",\"asset_class\":\"us_equity\",\"qty\":\"100\","
                    + "\"side\":\"long\",\"avg_entry_price\":\"190.50\"},"
                    + "{\"symbol\":\"SPY260519C00737000\",\"asset_class\":\"us_option\","
                    + "\"qty\":\"5\",\"side\":\"long\",\"avg_entry_price\":\"0.84\"},"
                    + "{\"symbol\":\"NVDA260516P00100000\",\"asset_class\":\"us_option\","
                    + "\"qty\":\"-2\",\"side\":\"short\",\"avg_entry_price\":\"1.10\"}]"));

    List<BrokerPosition> positions = broker.listOpenPositions();

    assertThat(positions).hasSize(1);
    BrokerPosition pos = positions.get(0);
    assertThat(pos.getOptionSymbol()).isEqualTo("SPY260519C00737000");
    assertThat(pos.getQty()).isEqualTo(5L);
    assertThat(pos.getSide()).isEqualTo(BrokerPosition.Side.LONG);
    assertThat(pos.getAvgEntryPrice()).isEqualByComparingTo(new BigDecimal("0.84"));

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("GET");
    assertThat(req.getPath()).isEqualTo("/v2/positions");
  }

  @Test
  void listOpenPositions_emptyArray_returnsEmptyList() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("[]"));

    List<BrokerPosition> positions = broker.listOpenPositions();

    assertThat(positions).isEmpty();
  }

  @Test
  void getAccountEquity_readsEquityFieldNotBuyingPower() throws Exception {
    // Issue #317: /v2/account returns both `equity` (net liquidation) and `buying_power` as
    // distinct fields. The notional-cap gate compares against net liquidation, so we must surface
    // `equity`, never `buying_power`. The two values are deliberately different here so a
    // regression
    // that reads the wrong field is caught.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"123456.78\",\"buying_power\":\"999999.00\","
                    + "\"cash\":\"5000.00\",\"status\":\"ACTIVE\"}"));

    BigDecimal equity = broker.getAccountEquity();

    assertThat(equity).isEqualByComparingTo(new BigDecimal("123456.78"));

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("GET");
    assertThat(req.getPath()).isEqualTo("/v2/account");
    assertThat(req.getHeader("APCA-API-KEY-ID")).isEqualTo("key-id-for-test");
  }

  @Test
  void getAccountEquity_unauthorized_throwsAuthErrorNonRetryable() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(401)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"access key verification failed\"}"));

    assertThatThrownBy(() -> broker.getAccountEquity())
        .isInstanceOf(ApplicationFailure.class)
        .satisfies(t -> assertThat(((ApplicationFailure) t).getType()).isEqualTo("AuthError"));
  }

  @Test
  void getAccountEquity_missingEquityField_throwsProtocolError() {
    // A 200 with no `equity` field is a protocol breach: the gate would otherwise silently
    // fail-closed on a parse default, masking a real Alpaca contract change.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"id\":\"acct-1\",\"buying_power\":\"999999.00\"}"));

    assertThatThrownBy(() -> broker.getAccountEquity())
        .isInstanceOf(ApplicationFailure.class)
        .satisfies(
            t -> assertThat(((ApplicationFailure) t).getType()).isEqualTo("BrokerProtocolError"));
  }

  @Test
  void getAccountCash_readsCashFieldNotBuyingPowerOrEquity() throws Exception {
    // Issue #323: the notional-cap gate's MTM-stable denominator is the cost-basis capital base
    // (cash + sum_open_notional), so the cap reads `cash`. `cash`, `equity`, and `buying_power` are
    // deliberately distinct so a regression that reads the wrong field is caught.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"123456.78\",\"buying_power\":\"999999.00\","
                    + "\"cash\":\"5000.00\",\"status\":\"ACTIVE\"}"));

    BigDecimal cash = broker.getAccountCash();

    assertThat(cash).isEqualByComparingTo(new BigDecimal("5000.00"));

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("GET");
    assertThat(req.getPath()).isEqualTo("/v2/account");
  }

  @Test
  void getAccountCash_missingCashField_throwsProtocolError() {
    // A 200 with no `cash` field is a protocol breach: the cap gate would otherwise lose its
    // denominator. Mirror the null-equity breach so the gate fails closed rather than passing an
    // unbounded cap.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"123456.78\",\"buying_power\":\"999999.00\"}"));

    assertThatThrownBy(() -> broker.getAccountCash())
        .isInstanceOf(ApplicationFailure.class)
        .satisfies(
            t -> assertThat(((ApplicationFailure) t).getType()).isEqualTo("BrokerProtocolError"));
  }

  @Test
  void getAccount_readsEquityAndCashFromSingleAccountRequest() throws Exception {
    // Issue #323 single-fetch: getAccount() must extract BOTH equity and cash from ONE /v2/account
    // round-trip. The AccountSnapshotActivity reads both per invocation; calling getAccountEquity()
    // and getAccountCash() separately would issue two GET /v2/account requests. Only one response
    // is
    // enqueued, so a second round-trip would fail; we also assert exactly one recorded request.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"123456.78\",\"buying_power\":\"999999.00\","
                    + "\"cash\":\"5000.00\",\"status\":\"ACTIVE\"}"));

    OptionsBroker.AccountSummary account = broker.getAccount();

    assertThat(account.equity()).isEqualByComparingTo(new BigDecimal("123456.78"));
    assertThat(account.cash()).isEqualByComparingTo(new BigDecimal("5000.00"));

    assertThat(server.getRequestCount()).isEqualTo(1);
    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("GET");
    assertThat(req.getPath()).isEqualTo("/v2/account");
  }

  @Test
  void getAccount_missingEquityField_throwsProtocolError() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"id\":\"acct-1\",\"cash\":\"5000.00\",\"buying_power\":\"999999.00\"}"));

    assertThatThrownBy(() -> broker.getAccount())
        .isInstanceOf(ApplicationFailure.class)
        .satisfies(
            t -> assertThat(((ApplicationFailure) t).getType()).isEqualTo("BrokerProtocolError"));
  }

  @Test
  void getAccount_missingCashField_throwsProtocolError() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"123456.78\",\"buying_power\":\"999999.00\"}"));

    assertThatThrownBy(() -> broker.getAccount())
        .isInstanceOf(ApplicationFailure.class)
        .satisfies(
            t -> assertThat(((ApplicationFailure) t).getType()).isEqualTo("BrokerProtocolError"));
  }

  @Test
  void preTradeCheck_readsOptionsBuyingPower_whenPresent() throws Exception {
    // Issue #320: the pre-trade gate prefers options_buying_power. Here it is present and distinct
    // from the general buying_power so a regression that reads the wrong field is caught.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"50000.00\",\"buying_power\":\"100000.00\","
                    + "\"options_buying_power\":\"40000.00\",\"pattern_day_trader\":false,"
                    + "\"daytrade_count\":0,\"multiplier\":\"2\"}"));

    PreTradeCheckResult r = broker.preTradeCheck(preTradeRequest(new BigDecimal("1000")));

    assertThat(r.getAllowed()).isTrue();
    assertThat(r.getBuyingPower()).isEqualByComparingTo(new BigDecimal("40000.00"));
    assertThat(r.getPdtStatus()).isEqualTo(PreTradeCheckResult.PdtStatus.OK);
    assertThat(r.getMarginSufficient()).isTrue();
    // The fallback counter must NOT fire on the happy path (options_buying_power present), so it
    // stays at zero.
    assertThat(
            meterRegistry
                .get(AlpacaPaperBroker.BUYING_POWER_FALLBACK_COUNTER_NAME)
                .counter()
                .count())
        .isEqualTo(0.0);

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("GET");
    assertThat(req.getPath()).isEqualTo("/v2/account");
    assertThat(req.getHeader("APCA-API-KEY-ID")).isEqualTo("key-id-for-test");
  }

  @Test
  void preTradeCheck_fallsBackToBuyingPower_whenOptionsFieldAbsent() {
    // Issue #320 criterion 2: when options_buying_power is absent, fall back to buying_power. Issue
    // #327 task 3: this fallback branch also logs a WARN (raw buying_power on a Reg-T margin
    // account
    // can be 2-4x the correct options figure → looser gate). Asserting getBuyingPower() equals the
    // raw buying_power value confirms the fallback (WARN) branch ran.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"50000.00\",\"buying_power\":\"75000.00\","
                    + "\"pattern_day_trader\":false,\"daytrade_count\":0,\"multiplier\":\"2\"}"));

    PreTradeCheckResult r = broker.preTradeCheck(preTradeRequest(new BigDecimal("1000")));

    assertThat(r.getBuyingPower()).isEqualByComparingTo(new BigDecimal("75000.00"));
    // The fallback also bumps a Micrometer counter so a persistently-looser funding gate is
    // observable (not just a buried WARN). Asserting count == 1.0 proves the fallback ran.
    assertThat(
            meterRegistry
                .get(AlpacaPaperBroker.BUYING_POWER_FALLBACK_COUNTER_NAME)
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void preTradeCheck_pdtFlaggedNullDaytradeCount_failsClosedWithProtocolError() {
    // Fail-closed (issue #327 task 1): a flagged pattern-day-trader account whose 200 response
    // omits
    // `daytrade_count` cannot be evaluated for the PDT block. It must fail CLOSED with
    // BrokerProtocolError rather than silently admitting (fail-OPEN) — mirroring the null-equity
    // protocol breach for an over-limit flagged account.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"15000.00\",\"buying_power\":\"30000.00\","
                    + "\"options_buying_power\":\"30000.00\",\"pattern_day_trader\":true,"
                    + "\"multiplier\":\"2\"}"));

    assertThatThrownBy(() -> broker.preTradeCheck(preTradeRequest(new BigDecimal("1000"))))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("BrokerProtocolError");
              assertThat(f.isNonRetryable()).isTrue();
            });
  }

  @Test
  void preTradeCheck_serverError_failsClosed() {
    // Fail-closed (issue #327 task 2): a 5xx on /v2/account must NOT yield an allowed result. The
    // mapError 5xx path re-throws HttpStatusCodeException so Temporal's bounded retry → reject
    // applies. Mirrors preTradeCheck_unauthorized_failsClosedWithAuthError and the
    // cancelOrder_503_isRetryable 5xx assertion shape.
    server.enqueue(
        new MockResponse()
            .setResponseCode(503)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"service unavailable\"}"));

    assertThatThrownBy(() -> broker.preTradeCheck(preTradeRequest(new BigDecimal("1000"))))
        .isInstanceOf(HttpStatusCodeException.class);
  }

  @Test
  void preTradeCheck_pdtBlocked_whenFlaggedOverLimitOnSub25kAccount() {
    // Issue #320 criterion 3: pattern_day_trader flagged + daytrade_count over the 3-trade limit on
    // a sub-$25k account derives BLOCKED.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"15000.00\",\"buying_power\":\"30000.00\","
                    + "\"options_buying_power\":\"30000.00\",\"pattern_day_trader\":true,"
                    + "\"daytrade_count\":4,\"multiplier\":\"2\"}"));

    PreTradeCheckResult r = broker.preTradeCheck(preTradeRequest(new BigDecimal("1000")));

    assertThat(r.getPdtStatus()).isEqualTo(PreTradeCheckResult.PdtStatus.BLOCKED);
  }

  @Test
  void preTradeCheck_pdtBlocked_whenDaytradeCountEqualsLimitBoundary() {
    // Boundary: daytrade_count == PDT_DAYTRADE_LIMIT (3) on a flagged sub-$25k account is BLOCKED.
    // The over-limit BLOCKED test uses 4; this pins the inclusive lower edge (>= 3).
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"15000.00\",\"buying_power\":\"30000.00\","
                    + "\"options_buying_power\":\"30000.00\",\"pattern_day_trader\":true,"
                    + "\"daytrade_count\":3,\"multiplier\":\"2\"}"));

    PreTradeCheckResult r = broker.preTradeCheck(preTradeRequest(new BigDecimal("1000")));

    assertThat(r.getPdtStatus()).isEqualTo(PreTradeCheckResult.PdtStatus.BLOCKED);
  }

  @Test
  void preTradeCheck_pdtOk_whenDaytradeCountJustBelowLimit() {
    // Boundary: daytrade_count == 2 (one below the 3-trade limit) on a flagged sub-$25k account is
    // OK — the day-trade limit has not been reached.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"15000.00\",\"buying_power\":\"30000.00\","
                    + "\"options_buying_power\":\"30000.00\",\"pattern_day_trader\":true,"
                    + "\"daytrade_count\":2,\"multiplier\":\"2\"}"));

    PreTradeCheckResult r = broker.preTradeCheck(preTradeRequest(new BigDecimal("1000")));

    assertThat(r.getPdtStatus()).isEqualTo(PreTradeCheckResult.PdtStatus.OK);
  }

  @Test
  void preTradeCheck_pdtOk_whenEquityExactlyAt25kThreshold() {
    // Boundary: equity == $25,000 exactly is OK — isPdtBlocked uses a strict `<
    // PDT_EQUITY_THRESHOLD`
    // comparison, so the threshold value itself is not blocked.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"25000\",\"buying_power\":\"50000.00\","
                    + "\"options_buying_power\":\"50000.00\",\"pattern_day_trader\":true,"
                    + "\"daytrade_count\":9,\"multiplier\":\"2\"}"));

    PreTradeCheckResult r = broker.preTradeCheck(preTradeRequest(new BigDecimal("1000")));

    assertThat(r.getPdtStatus()).isEqualTo(PreTradeCheckResult.PdtStatus.OK);
  }

  @Test
  void preTradeCheck_marginSufficient_whenNotionalNull() {
    // Null estimated_notional exercises the `notional == null ||` fast path: with no notional to
    // compare, margin_sufficient is true regardless of buying power.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"50000.00\",\"buying_power\":\"100.00\","
                    + "\"options_buying_power\":\"100.00\",\"pattern_day_trader\":false,"
                    + "\"daytrade_count\":0,\"multiplier\":\"2\"}"));

    PreTradeCheckResult r = broker.preTradeCheck(preTradeRequest(null));

    assertThat(r.getMarginSufficient()).isTrue();
  }

  @Test
  void preTradeCheck_pdtFlaggedOverLimitMissingEquity_failsClosedWithProtocolError() {
    // Fail-closed (fix for the null-equity gap): a flagged pattern-day-trader account over the
    // day-trade limit whose 200 response omits `equity` cannot be evaluated for the PDT block. It
    // must fail CLOSED with BrokerProtocolError — never a silent OK that admits a possibly-barred
    // trade. Mirrors the missing-buying_power / null-equity protocol breaches elsewhere.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"buying_power\":\"30000.00\","
                    + "\"options_buying_power\":\"30000.00\",\"pattern_day_trader\":true,"
                    + "\"daytrade_count\":4,\"multiplier\":\"2\"}"));

    assertThatThrownBy(() -> broker.preTradeCheck(preTradeRequest(new BigDecimal("1000"))))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("BrokerProtocolError");
              assertThat(f.isNonRetryable()).isTrue();
            });
  }

  @Test
  void preTradeCheck_pdtOk_whenFlaggedButEquityAtOrAbove25k() {
    // A flagged-and-over-limit account is NOT blocked once equity >= $25k: the PDT rule only gates
    // sub-$25k accounts.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"30000.00\",\"buying_power\":\"60000.00\","
                    + "\"options_buying_power\":\"60000.00\",\"pattern_day_trader\":true,"
                    + "\"daytrade_count\":9,\"multiplier\":\"4\"}"));

    PreTradeCheckResult r = broker.preTradeCheck(preTradeRequest(new BigDecimal("1000")));

    assertThat(r.getPdtStatus()).isEqualTo(PreTradeCheckResult.PdtStatus.OK);
  }

  @Test
  void preTradeCheck_marginInsufficient_whenNotionalExceedsBuyingPower() {
    // Issue #320 criterion 4: margin_sufficient is false when the requested notional exceeds the
    // available buying power.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"5000.00\",\"buying_power\":\"5000.00\","
                    + "\"options_buying_power\":\"500.00\",\"pattern_day_trader\":false,"
                    + "\"daytrade_count\":0,\"multiplier\":\"1\"}"));

    PreTradeCheckResult r = broker.preTradeCheck(preTradeRequest(new BigDecimal("1000")));

    assertThat(r.getMarginSufficient()).isFalse();
  }

  @Test
  void preTradeCheck_marginSufficient_whenBuyingPowerCoversNotional() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"50000.00\",\"buying_power\":\"100000.00\","
                    + "\"options_buying_power\":\"100000.00\",\"pattern_day_trader\":false,"
                    + "\"daytrade_count\":0,\"multiplier\":\"2\"}"));

    PreTradeCheckResult r = broker.preTradeCheck(preTradeRequest(new BigDecimal("1000")));

    assertThat(r.getMarginSufficient()).isTrue();
  }

  @Test
  void preTradeCheck_unauthorized_failsClosedWithAuthError() {
    // Issue #320 criterion 6: a broker exception (401/5xx) on /v2/account fails closed — the
    // override raises a non-retryable ApplicationFailure mirroring getAccountEquity's mapping, so
    // the workflow's fail-closed path rejects.
    server.enqueue(
        new MockResponse()
            .setResponseCode(401)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"access key verification failed\"}"));

    assertThatThrownBy(() -> broker.preTradeCheck(preTradeRequest(new BigDecimal("1000"))))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("AuthError");
              assertThat(f.isNonRetryable()).isTrue();
            });
  }

  @Test
  void preTradeCheck_missingBuyingPowerFields_failsClosedWithProtocolError() {
    // A 200 with neither options_buying_power nor buying_power is a protocol breach: the gate must
    // fail closed rather than silently passing a null buying power.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"id\":\"acct-1\",\"equity\":\"50000.00\"}"));

    assertThatThrownBy(() -> broker.preTradeCheck(preTradeRequest(new BigDecimal("1000"))))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> assertThat(f.getType()).isEqualTo("BrokerProtocolError"));
  }

  @Test
  void preTradeCheck_issuesExactlyOneAccountRequest() throws Exception {
    // Issue #320 criterion 8: a single preTradeCheck invocation issues exactly one /v2/account
    // request — no second round-trip per signal.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"50000.00\",\"buying_power\":\"100000.00\","
                    + "\"options_buying_power\":\"100000.00\",\"pattern_day_trader\":false,"
                    + "\"daytrade_count\":0,\"multiplier\":\"2\"}"));

    broker.preTradeCheck(preTradeRequest(new BigDecimal("1000")));

    assertThat(server.getRequestCount()).isEqualTo(1);
    RecordedRequest req = server.takeRequest();
    assertThat(req.getPath()).isEqualTo("/v2/account");
  }

  private static PreTradeCheckRequest preTradeRequest(BigDecimal estimatedNotional) {
    PreTradeCheckRequest req = new PreTradeCheckRequest();
    req.setSchemaVersion(1L);
    req.setTenantId("t1");
    req.setStrategyId("s1");
    req.setBrokerTarget(PreTradeCheckRequest.BrokerTarget.ALPACA_PAPER);
    req.setOptionSymbol("NVDA260516C00140000");
    req.setSide(PreTradeCheckRequest.Side.BUY);
    req.setQty(1L);
    req.setEstimatedNotional(estimatedNotional);
    return req;
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
