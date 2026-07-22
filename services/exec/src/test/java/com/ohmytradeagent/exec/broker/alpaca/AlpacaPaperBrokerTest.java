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
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
  void placeOrder_duplicateClientOrderId_noExistingId_liveLookup_returnsAlreadyExisted()
      throws Exception {
    // B1: a retried placement re-POSTs the same client_order_id; Alpaca answers 422
    // "client_order_id must be unique" WITHOUT existing_order_id. The adapter must NOT crash
    // (non-retryable InvalidRequestError) — it resolves the prior order by client_order_id and,
    // because that order is LIVE, returns alreadyExisted=true so the workflow proceeds to the fill.
    server.enqueue(
        new MockResponse()
            .setResponseCode(422)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"client_order_id must be unique\"}"));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"alp-live-7\",\"client_order_id\":\"intent-A\",\"status\":\"accepted\"}"));

    PlaceOrderResponse r = broker.placeOrder(request("intent-A"));

    assertThat(r.brokerOrderId()).isEqualTo("alp-live-7");
    assertThat(r.alreadyExisted()).isTrue();

    server.takeRequest(); // POST /v2/orders
    RecordedRequest lookup = server.takeRequest();
    assertThat(lookup.getMethod()).isEqualTo("GET");
    assertThat(lookup.getPath())
        .isEqualTo("/v2/orders:by_client_order_id?client_order_id=intent-A");
  }

  @Test
  void placeOrder_duplicateClientOrderId_noExistingId_terminalLookup_rethrowsRetryable() {
    // B1 strict live-only: the by-cid lookup surfaces a TERMINAL order (canceled/expired).
    // Returning
    // alreadyExisted=true would strand the workflow awaiting a fill that never comes, so the
    // adapter
    // rethrows the ORIGINAL 422 as a retryable HttpStatusCodeException (NOT a non-retryable crash)
    // —
    // a fresh placement can then proceed, falling through to the B2 backstop if it keeps colliding.
    server.enqueue(
        new MockResponse()
            .setResponseCode(422)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"client_order_id must be unique\"}"));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"alp-dead-7\",\"client_order_id\":\"intent-A\",\"status\":\"expired\"}"));

    assertThatThrownBy(() -> broker.placeOrder(request("intent-A")))
        .isInstanceOf(HttpStatusCodeException.class)
        .isNotInstanceOf(ApplicationFailure.class);
  }

  @Test
  void placeOrder_duplicateClientOrderId_noExistingId_lookupNotFound_rethrowsRetryable() {
    // B1: the by-cid lookup returns nothing (404 / sub-second visibility window). The adapter must
    // rethrow the original 422 as retryable so Temporal retries — NEVER a non-retryable crash.
    server.enqueue(
        new MockResponse()
            .setResponseCode(422)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"client_order_id must be unique\"}"));
    server.enqueue(
        new MockResponse()
            .setResponseCode(404)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"order not found\"}"));

    assertThatThrownBy(() -> broker.placeOrder(request("intent-A")))
        .isInstanceOf(HttpStatusCodeException.class)
        .isNotInstanceOf(ApplicationFailure.class);
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
  void placeOrder_accountOrdersBlocked_throwsAccountOrdersBlockedErrorNonRetryable() {
    // prod_real intentional halt: Alpaca returns 403 {"code":40310000,"message":"new orders are
    // rejected by user request"} for an operator-halted account. This must fail fast as a terminal,
    // non-retryable AccountOrdersBlockedError (not burn 6 retries while parking in RECORDED).
    server.enqueue(
        new MockResponse()
            .setResponseCode(403)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"code\":40310000,\"message\":\"new orders are rejected by user request\"}"));

    assertThatThrownBy(() -> broker.placeOrder(request("intent-A")))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType())
                  .isEqualTo(AlpacaPaperBroker.ACCOUNT_ORDERS_BLOCKED_ERROR_TYPE);
              assertThat(f.isNonRetryable()).isTrue();
              assertThat(f.getMessage()).contains("new orders are rejected by user request");
            });
  }

  @Test
  void placeOrder_serverError5xx_rethrowsRetryable() {
    // Regression guard: a generic 5xx must stay a retryable HttpStatusCodeException (NOT a
    // non-retryable ApplicationFailure) so Temporal retries a transient broker outage.
    server.enqueue(
        new MockResponse()
            .setResponseCode(503)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"service unavailable\"}"));

    assertThatThrownBy(() -> broker.placeOrder(request("intent-A")))
        .isInstanceOf(org.springframework.web.client.HttpStatusCodeException.class)
        .isNotInstanceOf(ApplicationFailure.class);
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
  void placeOrder_sellOverExit422_brokerConfirmsFlat_returnsAlreadyClosed() throws Exception {
    // PLAN-over-exit-422: an STC that lands AFTER the lot is already flat draws Alpaca's
    // "position intent mismatch, inferred: sell_to_open" 422. The adapter must NOT crash; it
    // cross-checks /v2/positions, finds the OCC absent, and returns the benign already-closed
    // response so the activity terminalizes the journal without paging.
    server.enqueue(
        new MockResponse()
            .setResponseCode(422)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"message\":\"position intent mismatch, inferred: sell_to_open, "
                    + "specified: sell_to_close\"}"));
    // /v2/positions: the OCC is absent (only an unrelated holding) → broker confirms flat.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "[{\"symbol\":\"SPY260519C00737000\",\"asset_class\":\"us_option\","
                    + "\"qty\":\"5\",\"side\":\"long\",\"avg_entry_price\":\"0.84\"}]"));

    PlaceOrderResponse r = broker.placeOrder(sellRequest("intent-A"));

    assertThat(r.alreadyClosed()).isTrue();
    assertThat(r.alreadyExisted()).isFalse();
    assertThat(r.brokerOrderId()).isNull();

    assertThat(server.takeRequest().getPath()).isEqualTo("/v2/orders");
    assertThat(server.takeRequest().getPath()).isEqualTo("/v2/positions");
  }

  @Test
  void placeOrder_sellOverExit422_brokerStillHoldsQty_throwsMapErrorNotBenign() {
    // The 422 carried the over-exit signature, but /v2/positions STILL reports qty>0 for this OCC —
    // so the rejection was NOT a true over-exit (e.g. a transient insufficient-qty from an
    // in-flight
    // sibling). The change must be a no-op: fall through to the existing mapError failure path so a
    // genuinely-still-open lot is never silently abandoned.
    server.enqueue(
        new MockResponse()
            .setResponseCode(422)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"position intent mismatch, inferred: sell_to_open\"}"));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "[{\"symbol\":\"NVDA260516C00140000\",\"asset_class\":\"us_option\","
                    + "\"qty\":\"3\",\"side\":\"long\",\"avg_entry_price\":\"2.10\"}]"));

    assertThatThrownBy(() -> broker.placeOrder(sellRequest("intent-A")))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("InvalidRequestError");
              assertThat(f.isNonRetryable()).isTrue();
            });
  }

  @Test
  void placeOrder_sellOverExit422_positionsCallThrows_fallsThroughToFailure() {
    // Fail-safe: a /v2/positions call that throws means we cannot CONFIRM flat. We must fall
    // through
    // to the failure path (keep the lot managed), never benignly abandon it.
    server.enqueue(
        new MockResponse()
            .setResponseCode(422)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"position intent mismatch, inferred: sell_to_open\"}"));
    server.enqueue(
        new MockResponse()
            .setResponseCode(500)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"positions unavailable\"}"));

    assertThatThrownBy(() -> broker.placeOrder(sellRequest("intent-A")))
        .isInstanceOf(ApplicationFailure.class);
  }

  @Test
  void placeOrder_buyWithOverExitBody_throwsMapError_noPositionsCrossCheck() {
    // BUY/BTO is unaffected by an over-exit: the SELL-only guard must not fire, so the same 422
    // body
    // maps straight through mapError with NO /v2/positions call (only one request is enqueued).
    server.enqueue(
        new MockResponse()
            .setResponseCode(422)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"position intent mismatch, inferred: sell_to_open\"}"));

    assertThatThrownBy(() -> broker.placeOrder(request("intent-A")))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> assertThat(f.getType()).isEqualTo("InvalidRequestError"));
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
        new PlaceOrderRequest("t-dev", "intent-A", "NVDA  260516C00140000", "BUY", 1L, null);
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
  void tradingDays_parsesCalendarPayloadAndSendsStartEndAndAuth() throws Exception {
    // Alpaca omits non-trading days; each returned entry is a trading day. 2026-07-03 (holiday)
    // is absent so the resolver later shifts a candidate Friday back to Thursday 2026-07-02.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "[{\"date\":\"2026-07-01\",\"open\":\"09:30\",\"close\":\"16:00\"},"
                    + "{\"date\":\"2026-07-02\",\"open\":\"09:30\",\"close\":\"16:00\"}]"));

    List<LocalDate> days = broker.tradingDays(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 3));

    assertThat(days).containsExactly(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 2));

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("GET");
    assertThat(req.getRequestUrl().encodedPath()).isEqualTo("/v2/calendar");
    assertThat(req.getRequestUrl().queryParameter("start")).isEqualTo("2026-07-01");
    assertThat(req.getRequestUrl().queryParameter("end")).isEqualTo("2026-07-03");
    assertThat(req.getHeader("APCA-API-KEY-ID")).isEqualTo("key-id-for-test");
    assertThat(req.getHeader("APCA-API-SECRET-KEY")).isEqualTo("key-secret-for-test");
  }

  @Test
  void tradingDays_skipsBlankAndUnparseableDatesDefensively() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "[{\"date\":\"2026-07-01\"},{\"date\":\"\"},{\"date\":\"not-a-date\"},"
                    + "{\"date\":null}]"));

    List<LocalDate> days = broker.tradingDays(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1));

    assertThat(days).containsExactly(LocalDate.of(2026, 7, 1));
  }

  @Test
  void getPortfolioHistory_parsesParallelArraysScalarsAndSendsQueryParams() throws Exception {
    // Live-account-view: /v2/account/portfolio/history returns parallel arrays indexed by
    // timestamp[] (epoch seconds) plus the base_value baseline + timeframe scalar. Assert every
    // PortfolioHistory field maps and the period/timeframe query params are sent (date_end
    // omitted).
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                // base_value_asof is a DATE STRING in Alpaca's real response (not an epoch number)
                // —
                // binding it to a Long previously threw a Jackson parse error that failed the whole
                // read (the /live "Account history unavailable" incident). Keep it as a string here
                // so
                // this test reproduces + guards that shape; the DTO drops it and baseValueAsof maps
                // null.
                "{\"timestamp\":[1719446400,1719532800],"
                    + "\"equity\":[10000.00,10120.50],"
                    + "\"profit_loss\":[0.00,120.50],"
                    + "\"profit_loss_pct\":[0.0,0.01205],"
                    + "\"base_value\":10000.00,\"base_value_asof\":\"2026-06-17\","
                    + "\"timeframe\":\"1D\"}"));

    OptionsBroker.PortfolioHistory h = broker.getPortfolioHistory("1M", "1D", null);

    assertThat(h.timestamps()).containsExactly(1719446400L, 1719532800L);
    assertThat(h.equity()).containsExactly(new BigDecimal("10000.00"), new BigDecimal("10120.50"));
    assertThat(h.profitLoss()).containsExactly(new BigDecimal("0.00"), new BigDecimal("120.50"));
    assertThat(h.profitLossPct()).containsExactly(new BigDecimal("0.0"), new BigDecimal("0.01205"));
    assertThat(h.baseValue()).isEqualByComparingTo(new BigDecimal("10000.00"));
    // Alpaca's date-string base_value_asof is intentionally dropped (unused by the UI) → null.
    assertThat(h.baseValueAsof()).isNull();
    assertThat(h.timeframe()).isEqualTo("1D");

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("GET");
    assertThat(req.getRequestUrl().encodedPath()).isEqualTo("/v2/account/portfolio/history");
    assertThat(req.getRequestUrl().queryParameter("period")).isEqualTo("1M");
    assertThat(req.getRequestUrl().queryParameter("timeframe")).isEqualTo("1D");
    // date_end is null → the param must be omitted entirely.
    assertThat(req.getRequestUrl().queryParameter("date_end")).isNull();
    assertThat(req.getHeader("APCA-API-KEY-ID")).isEqualTo("key-id-for-test");
  }

  @Test
  void getPortfolioHistory_unauthorized_throwsAuthErrorNonRetryable() {
    // A 4xx maps through mapError exactly like the account reads (401 → non-retryable AuthError).
    server.enqueue(
        new MockResponse()
            .setResponseCode(401)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"access key verification failed\"}"));

    assertThatThrownBy(() -> broker.getPortfolioHistory("1M", "1D", null))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("AuthError");
              assertThat(f.isNonRetryable()).isTrue();
            });
  }

  @Test
  void getAccountActivities_mapsSignedCashFlowsAndSendsActivityTypesAfterUntil() throws Exception {
    // Live-account-view deposit-adjustment: /v2/account/activities returns cash flows we net out of
    // the range return. A CSD (deposit +), CSW (withdrawal −), and JNLC (cash journal) map to
    // AccountCashFlow with the net_amount sign preserved and the "YYYY-MM-DD" date parsed to
    // midnight-UTC epoch seconds. Assert the request carries activity_types=CSD,CSW,JNLC and the
    // after/until window (ISO-8601 of the two epoch-second bounds).
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "[{\"activity_type\":\"CSD\",\"net_amount\":\"41230.00\",\"date\":\"2026-07-15\"},"
                    + "{\"activity_type\":\"CSW\",\"net_amount\":\"-500.00\",\"date\":\"2026-07-16\"},"
                    + "{\"activity_type\":\"JNLC\",\"net_amount\":\"25.00\",\"date\":\"2026-07-17\"}]"));

    // 2026-07-01T00:00:00Z .. 2026-07-20T00:00:00Z
    long start = 1751328000L;
    long end = 1753142400L;
    List<OptionsBroker.AccountCashFlow> flows = broker.getAccountActivities(start, end);

    assertThat(flows).hasSize(3);
    assertThat(flows.get(0).amount()).isEqualByComparingTo(new BigDecimal("41230.00"));
    assertThat(flows.get(0).timestamp())
        .isEqualTo(
            LocalDate.of(2026, 7, 15).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond());
    assertThat(flows.get(1).amount()).isEqualByComparingTo(new BigDecimal("-500.00"));
    assertThat(flows.get(2).amount()).isEqualByComparingTo(new BigDecimal("25.00"));

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("GET");
    assertThat(req.getRequestUrl().encodedPath()).isEqualTo("/v2/account/activities");
    assertThat(req.getRequestUrl().queryParameter("activity_types")).isEqualTo("CSD,CSW,JNLC");
    assertThat(req.getRequestUrl().queryParameter("after"))
        .isEqualTo(java.time.Instant.ofEpochSecond(start).toString());
    assertThat(req.getRequestUrl().queryParameter("until"))
        .isEqualTo(java.time.Instant.ofEpochSecond(end).toString());
    assertThat(req.getHeader("APCA-API-KEY-ID")).isEqualTo("key-id-for-test");
  }

  @Test
  void getAccountActivities_serverError_throwsReadWordedFailureNotOrderWorded() {
    // The activities read must NOT be classified through the order-path mapError: that helper's
    // branches are order-worded, so an activities body carrying e.g. "insufficient buying power"
    // would produce "Alpaca rejected order: ..." for an order that was never placed. Assert the
    // read-specific type + wording instead. The caller degrades to cash_flows_available=false
    // either way.
    server.enqueue(
        new MockResponse()
            .setResponseCode(500)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"message\":\"insufficient buying power\"}"));

    assertThatThrownBy(() -> broker.getAccountActivities(1751328000L, 1753142400L))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType())
                  .isEqualTo(AlpacaPaperBroker.ACCOUNT_ACTIVITIES_READ_ERROR_TYPE);
              assertThat(f.getOriginalMessage()).contains("/v2/account/activities read failed");
              assertThat(f.getOriginalMessage()).doesNotContain("rejected order");
            });
  }

  @Test
  void getAccountActivities_slowResponse_failsFastOnReadTimeoutInsteadOfHangingTheActivity() {
    // The cash-flow lookup is a SECOND call inside the portfolio-history Activity, which has one
    // 15s StartToCloseTimeout covering both calls. A slow-but-not-erroring activities endpoint
    // must NOT be able to burn that shared budget (which would make Temporal retry the entire
    // Activity, including the already-successful portfolio-history read). Assert the call is
    // bounded by its own read timeout and surfaces a RuntimeException the caller degrades on.
    RestClient client =
        RestClient.builder()
            .baseUrl(server.url("/").toString().replaceAll("/$", ""))
            .defaultHeader("APCA-API-KEY-ID", "key-id-for-test")
            .defaultHeader("APCA-API-SECRET-KEY", "key-secret-for-test")
            .defaultHeader("Accept", "application/json")
            .build();
    AlpacaPaperBroker bounded =
        new AlpacaPaperBroker(
            client, mapper, meterRegistry, Duration.ofMillis(200), Duration.ofMillis(200));

    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("[]")
            .setBodyDelay(3, TimeUnit.SECONDS));

    long startedAt = System.nanoTime();
    assertThatThrownBy(() -> bounded.getAccountActivities(1751328000L, 1753142400L))
        .isInstanceOf(RuntimeException.class);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
    assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
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
            "t-dev", "intent-422", "NVDA  260516C00140000", "BUY", 1L, new BigDecimal("2.30"));

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
    enqueueStatus("replaced");
    enqueueStatus("expired");
    enqueueStatus("rejected");
    enqueueStatus("some_future_status");

    assertThat(broker.getOrderStatus("alp-1")).isEqualTo(BrokerOrderStatus.OPEN);
    assertThat(broker.getOrderStatus("alp-1")).isEqualTo(BrokerOrderStatus.OPEN);
    assertThat(broker.getOrderStatus("alp-1")).isEqualTo(BrokerOrderStatus.OPEN);
    assertThat(broker.getOrderStatus("alp-1")).isEqualTo(BrokerOrderStatus.FILLED);
    // canceled → CANCELLED
    assertThat(broker.getOrderStatus("alp-1")).isEqualTo(BrokerOrderStatus.CANCELLED);
    // replaced still → CANCELLED
    assertThat(broker.getOrderStatus("alp-1")).isEqualTo(BrokerOrderStatus.CANCELLED);
    // Part A: expired now maps to its own EXPIRED terminal (was CANCELLED).
    assertThat(broker.getOrderStatus("alp-1")).isEqualTo(BrokerOrderStatus.EXPIRED);
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
                    + "\"qty\":\"5\",\"side\":\"long\",\"avg_entry_price\":\"0.84\","
                    + "\"current_price\":\"1.20\",\"market_value\":\"600.00\","
                    + "\"unrealized_pl\":\"180.00\",\"unrealized_intraday_pl\":\"-15.00\"},"
                    + "{\"symbol\":\"NVDA260516P00100000\",\"asset_class\":\"us_option\","
                    + "\"qty\":\"-2\",\"side\":\"short\",\"avg_entry_price\":\"1.10\"}]"));

    List<BrokerPosition> positions = broker.listOpenPositions();

    assertThat(positions).hasSize(1);
    BrokerPosition pos = positions.get(0);
    assertThat(pos.getOptionSymbol()).isEqualTo("SPY260519C00737000");
    assertThat(pos.getQty()).isEqualTo(5L);
    assertThat(pos.getSide()).isEqualTo(BrokerPosition.Side.LONG);
    assertThat(pos.getAvgEntryPrice()).isEqualByComparingTo(new BigDecimal("0.84"));
    // Live marks (dashboard-only) pass through, including a SIGNED today's-P&L.
    assertThat(pos.getCurrentPrice()).isEqualByComparingTo(new BigDecimal("1.20"));
    assertThat(pos.getMarketValue()).isEqualByComparingTo(new BigDecimal("600.00"));
    assertThat(pos.getUnrealizedPl()).isEqualByComparingTo(new BigDecimal("180.00"));
    assertThat(pos.getUnrealizedIntradayPl()).isEqualByComparingTo(new BigDecimal("-15.00"));

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("GET");
    assertThat(req.getPath()).isEqualTo("/v2/positions");
  }

  @Test
  void listOpenPositions_absentMarks_leavesMarkFieldsNull() throws Exception {
    // A marks-free positions row (older Alpaca shape / a broker that omits them) must leave the
    // mark fields null rather than defaulting to a misleading zero — the BFF then simply omits
    // them.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "[{\"symbol\":\"SPY260519C00737000\",\"asset_class\":\"us_option\","
                    + "\"qty\":\"5\",\"side\":\"long\",\"avg_entry_price\":\"0.84\"}]"));

    List<BrokerPosition> positions = broker.listOpenPositions();

    assertThat(positions).hasSize(1);
    BrokerPosition pos = positions.get(0);
    assertThat(pos.getCurrentPrice()).isNull();
    assertThat(pos.getMarketValue()).isNull();
    assertThat(pos.getUnrealizedPl()).isNull();
    assertThat(pos.getUnrealizedIntradayPl()).isNull();
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
                "{\"id\":\"acct-1\",\"account_number\":\"PA3ER05HLHMB\","
                    + "\"equity\":\"123456.78\",\"buying_power\":\"999999.00\","
                    + "\"cash\":\"5000.00\",\"status\":\"ACTIVE\"}"));

    OptionsBroker.AccountSummary account = broker.getAccount();

    assertThat(account.equity()).isEqualByComparingTo(new BigDecimal("123456.78"));
    assertThat(account.cash()).isEqualByComparingTo(new BigDecimal("5000.00"));
    // Informational account_number is mapped through (not used by any gate).
    assertThat(account.accountNumber()).isEqualTo("PA3ER05HLHMB");

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
  void preTradeCheck_marginAccount_gatesOnCash_notOptionsBuyingPower() throws Exception {
    // The point of this change: the affordability gate reads AVAILABLE CASH, not margin/options
    // buying power. On a Reg-T margin account options_buying_power (4000) is 2-4x the account's
    // actual cash (1000). Gating on the 4000 figure (the OLD behavior) would ADMIT a 2000-notional
    // order the account cannot fund with cash. The new gate reports buyingPower==cash==1000 and
    // margin_sufficient==false (1000 < 2000) → RiskActivitiesImpl REJECTS. This is the exact case
    // the old options-buying-power gate would have wrongly allowed.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"5000.00\",\"cash\":\"1000.00\","
                    + "\"buying_power\":\"8000.00\",\"options_buying_power\":\"4000.00\","
                    + "\"pattern_day_trader\":false,\"daytrade_count\":0,\"multiplier\":\"4\"}"));

    PreTradeCheckResult r = broker.preTradeCheck(preTradeRequest(new BigDecimal("2000")));

    assertThat(r.getAllowed()).isTrue();
    // The buying_power field now carries CASH (1000), NOT options_buying_power (4000).
    assertThat(r.getBuyingPower()).isEqualByComparingTo(new BigDecimal("1000.00"));
    // 1000 cash < 2000 notional → insufficient. Under the OLD options-buying-power gate (4000 >=
    // 2000) this would have been sufficient → the behavior differs, which is the whole point.
    assertThat(r.getMarginSufficient()).isFalse();
    assertThat(r.getPdtStatus()).isEqualTo(PreTradeCheckResult.PdtStatus.OK);

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("GET");
    assertThat(req.getPath()).isEqualTo("/v2/account");
    assertThat(req.getHeader("APCA-API-KEY-ID")).isEqualTo("key-id-for-test");
  }

  @Test
  void preTradeCheck_sufficientCash_reportsCashAndMarginSufficient() {
    // Sufficient-cash happy path: cash (5000) covers the 2000 notional → buyingPower==cash==5000
    // and margin_sufficient==true. options_buying_power is present but does NOT drive the result.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"10000.00\",\"cash\":\"5000.00\","
                    + "\"buying_power\":\"20000.00\",\"options_buying_power\":\"10000.00\","
                    + "\"pattern_day_trader\":false,\"daytrade_count\":0,\"multiplier\":\"4\"}"));

    PreTradeCheckResult r = broker.preTradeCheck(preTradeRequest(new BigDecimal("2000")));

    assertThat(r.getBuyingPower()).isEqualByComparingTo(new BigDecimal("5000.00"));
    assertThat(r.getMarginSufficient()).isTrue();
    assertThat(r.getPdtStatus()).isEqualTo(PreTradeCheckResult.PdtStatus.OK);
  }

  @Test
  void preTradeCheck_nullCash_failsClosedWithProtocolError() {
    // Fail-closed: a 200 that omits `cash` cannot establish the cash-affordability basis. The gate
    // must fail CLOSED with a non-retryable BrokerProtocolError rather than admitting an unchecked
    // order — mirroring the null-equity / missing-field protocol breaches elsewhere. options and
    // raw buying_power are present but MUST NOT be used as a fallback.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"50000.00\",\"buying_power\":\"100000.00\","
                    + "\"options_buying_power\":\"40000.00\",\"pattern_day_trader\":false,"
                    + "\"daytrade_count\":0,\"multiplier\":\"2\"}"));

    assertThatThrownBy(() -> broker.preTradeCheck(preTradeRequest(new BigDecimal("1000"))))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("BrokerProtocolError");
              assertThat(f.isNonRetryable()).isTrue();
            });
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
                "{\"id\":\"acct-1\",\"equity\":\"15000.00\",\"cash\":\"15000.00\","
                    + "\"buying_power\":\"30000.00\",\"options_buying_power\":\"30000.00\","
                    + "\"pattern_day_trader\":true,\"multiplier\":\"2\"}"));

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
                "{\"id\":\"acct-1\",\"equity\":\"15000.00\",\"cash\":\"15000.00\","
                    + "\"buying_power\":\"30000.00\",\"options_buying_power\":\"30000.00\","
                    + "\"pattern_day_trader\":true,\"daytrade_count\":4,\"multiplier\":\"2\"}"));

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
                "{\"id\":\"acct-1\",\"equity\":\"15000.00\",\"cash\":\"15000.00\","
                    + "\"buying_power\":\"30000.00\",\"options_buying_power\":\"30000.00\","
                    + "\"pattern_day_trader\":true,\"daytrade_count\":3,\"multiplier\":\"2\"}"));

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
                "{\"id\":\"acct-1\",\"equity\":\"15000.00\",\"cash\":\"15000.00\","
                    + "\"buying_power\":\"30000.00\",\"options_buying_power\":\"30000.00\","
                    + "\"pattern_day_trader\":true,\"daytrade_count\":2,\"multiplier\":\"2\"}"));

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
                "{\"id\":\"acct-1\",\"equity\":\"25000\",\"cash\":\"25000\","
                    + "\"buying_power\":\"50000.00\",\"options_buying_power\":\"50000.00\","
                    + "\"pattern_day_trader\":true,\"daytrade_count\":9,\"multiplier\":\"2\"}"));

    PreTradeCheckResult r = broker.preTradeCheck(preTradeRequest(new BigDecimal("1000")));

    assertThat(r.getPdtStatus()).isEqualTo(PreTradeCheckResult.PdtStatus.OK);
  }

  @Test
  void preTradeCheck_marginSufficient_whenNotionalNull() {
    // Null estimated_notional exercises the `notional == null ||` fast path: with no notional to
    // compare, margin_sufficient is true regardless of available cash.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"50000.00\",\"cash\":\"100.00\","
                    + "\"buying_power\":\"100.00\",\"options_buying_power\":\"100.00\","
                    + "\"pattern_day_trader\":false,\"daytrade_count\":0,\"multiplier\":\"2\"}"));

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
                "{\"id\":\"acct-1\",\"cash\":\"15000.00\",\"buying_power\":\"30000.00\","
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
                "{\"id\":\"acct-1\",\"equity\":\"30000.00\",\"cash\":\"30000.00\","
                    + "\"buying_power\":\"60000.00\",\"options_buying_power\":\"60000.00\","
                    + "\"pattern_day_trader\":true,\"daytrade_count\":9,\"multiplier\":\"4\"}"));

    PreTradeCheckResult r = broker.preTradeCheck(preTradeRequest(new BigDecimal("1000")));

    assertThat(r.getPdtStatus()).isEqualTo(PreTradeCheckResult.PdtStatus.OK);
  }

  @Test
  void preTradeCheck_marginInsufficient_whenNotionalExceedsCash() {
    // margin_sufficient is false when the requested notional exceeds available CASH — even though
    // options_buying_power (5000) would cover the 1000 notional, only the 500 cash counts.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"5000.00\",\"cash\":\"500.00\","
                    + "\"buying_power\":\"5000.00\",\"options_buying_power\":\"5000.00\","
                    + "\"pattern_day_trader\":false,\"daytrade_count\":0,\"multiplier\":\"1\"}"));

    PreTradeCheckResult r = broker.preTradeCheck(preTradeRequest(new BigDecimal("1000")));

    assertThat(r.getMarginSufficient()).isFalse();
  }

  @Test
  void preTradeCheck_marginSufficient_whenCashCoversNotional() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"50000.00\",\"cash\":\"100000.00\","
                    + "\"buying_power\":\"100000.00\",\"options_buying_power\":\"100000.00\","
                    + "\"pattern_day_trader\":false,\"daytrade_count\":0,\"multiplier\":\"2\"}"));

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
  void preTradeCheck_issuesExactlyOneAccountRequest() throws Exception {
    // Issue #320 criterion 8: a single preTradeCheck invocation issues exactly one /v2/account
    // request — no second round-trip per signal.
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"id\":\"acct-1\",\"equity\":\"50000.00\",\"cash\":\"100000.00\","
                    + "\"buying_power\":\"100000.00\",\"options_buying_power\":\"100000.00\","
                    + "\"pattern_day_trader\":false,\"daytrade_count\":0,\"multiplier\":\"2\"}"));

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
        "t-dev", clientOrderId, "NVDA  260516C00140000", "BUY", 1L, new BigDecimal("2.30"));
  }

  private static PlaceOrderRequest sellRequest(String clientOrderId) {
    // Same OCC (unpadded on the wire → NVDA260516C00140000) as request(), but a SELL/STC so the
    // over-exit cross-check is in scope.
    return new PlaceOrderRequest(
        "t-dev", clientOrderId, "NVDA  260516C00140000", "SELL", 6L, new BigDecimal("2.30"));
  }
}
