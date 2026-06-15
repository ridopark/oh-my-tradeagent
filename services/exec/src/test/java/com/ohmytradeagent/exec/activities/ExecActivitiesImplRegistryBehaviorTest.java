package com.ohmytradeagent.exec.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.exec.alert.BrokerRejectionAlerter;
import com.ohmytradeagent.exec.broker.ClientOrderId;
import com.ohmytradeagent.exec.broker.alpaca.AlpacaBrokerClientRegistry;
import com.ohmytradeagent.exec.broker.alpaca.AlpacaProperties;
import com.ohmytradeagent.exec.broker.alpaca.EnvFallbackBrokerCredentialSource;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import com.ohmytradeagent.exec.journal.OrderState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * P4-a behavior-preservation (load-bearing): with the env-fallback credential source + one tenant,
 * {@code ExecActivitiesImpl.placeOrder} resolves the broker through the REGISTRY and must issue the
 * byte-identical {@code /v2/orders} POST it did pre-P4-a. This drives the FULL path
 * ExecActivitiesImpl → AlpacaBrokerClientRegistry → EnvFallbackBrokerCredentialSource →
 * AlpacaPaperBroker against an in-process MockWebServer and asserts the order body + APCA headers.
 */
class ExecActivitiesImplRegistryBehaviorTest {

  private MockWebServer server;
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void start() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void stop() throws IOException {
    server.shutdown();
  }

  @Test
  void placeOrder_throughRegistry_issuesByteIdenticalAlpacaPost() throws Exception {
    String baseUrl = server.url("/").toString().replaceAll("/$", "");

    // env-fallback resolver: single env cred set, blank expected account (assertion disabled), so
    // no
    // /v2/account read precedes the order — identical to the pre-P4-a single-broker placement.
    EnvFallbackBrokerCredentialSource source =
        new EnvFallbackBrokerCredentialSource(
            new AlpacaProperties(baseUrl, "key-id-for-test", "key-secret-for-test"), "", "");
    // The MockWebServer URL is localhost (not a "paper" host), so use an impl suffix that leaves
    // the
    // base-url mode-coherence branch inert; the order body — the thing under test — is independent
    // of
    // the mode suffix. AlpacaPaperBrokerTest covers the paper/live coherence separately.
    AlpacaBrokerClientRegistry registry =
        new AlpacaBrokerClientRegistry(
            source, RestClient.builder(), mapper, new SimpleMeterRegistry(), "alpaca-x");

    OrderIntentJournal journal = mock(OrderIntentJournal.class);
    JournaledOrder recorded = recordedRow("intent-A");
    when(journal.findByIntentKey("intent-A")).thenReturn(Optional.of(recorded));
    when(journal.markSubmittedIfRecorded(anyString(), any())).thenReturn(true);

    ExecActivitiesImpl exec =
        new ExecActivitiesImpl(journal, registry, new BrokerRejectionAlerter(content -> {}, false));

    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"id\":\"alp-12345\",\"client_order_id\":\"cid\",\"status\":\"accepted\"}"));

    exec.placeOrder(intent("intent-A"));

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("POST");
    assertThat(req.getPath()).isEqualTo("/v2/orders");
    assertThat(req.getHeader("APCA-API-KEY-ID")).isEqualTo("key-id-for-test");
    assertThat(req.getHeader("APCA-API-SECRET-KEY")).isEqualTo("key-secret-for-test");

    JsonNode body = mapper.readTree(req.getBody().readUtf8());
    // The wire client_order_id is the bounded value derived from the intent_key (unchanged by
    // P4-a).
    assertThat(body.get("client_order_id").asText()).isEqualTo(ClientOrderId.forIntent("intent-A"));
    assertThat(body.get("symbol").asText()).isEqualTo("NVDA260516C00140000");
    assertThat(body.get("qty").asLong()).isEqualTo(1L);
    assertThat(body.get("side").asText()).isEqualTo("buy");
    assertThat(body.get("position_intent").asText()).isEqualTo("buy_to_open");
    assertThat(body.get("type").asText()).isEqualTo("limit");
    assertThat(body.get("time_in_force").asText()).isEqualTo("day");
    assertThat(body.get("limit_price").decimalValue()).isEqualByComparingTo("2.30");
    assertThat(body.has("order_class")).isFalse();
    assertThat(body.has("legs")).isFalse();
  }

  private static OrderIntent intent(String key) {
    OrderIntent i = new OrderIntent();
    i.setSchemaVersion(1L);
    i.setIntentKey(key);
    i.setSignalId("sig-1");
    i.setTenantId("dev");
    i.setStrategyId("copytrade-v1");
    i.setBrokerTarget(OrderIntent.BrokerTarget.ALPACA_PAPER);
    i.setOptionSymbol("NVDA  260516C00140000");
    i.setSide(OrderIntent.Side.BUY);
    i.setQty(1L);
    i.setLimitPrice(new BigDecimal("2.30"));
    i.setRecordedAt(OffsetDateTime.parse("2026-05-13T17:22:31Z"));
    return i;
  }

  private static JournaledOrder recordedRow(String intentKey) {
    return new JournaledOrder(
        intentKey,
        "sig-1",
        "dev",
        "copytrade-v1",
        "alpaca-paper",
        ClientOrderId.forIntent(intentKey),
        "NVDA  260516C00140000",
        "BUY",
        1L,
        new BigDecimal("2.30"),
        OrderState.RECORDED,
        null,
        OffsetDateTime.parse("2026-05-13T17:22:31Z"),
        null,
        OffsetDateTime.parse("2026-05-13T17:22:31Z"),
        null,
        null,
        null,
        null,
        null,
        0L);
  }
}
