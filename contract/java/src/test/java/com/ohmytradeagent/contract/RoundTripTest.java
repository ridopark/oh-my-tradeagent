package com.ohmytradeagent.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Round-trip sanity test for the generated DTOs.
 *
 * <p>Fixture JSON files in contract/schemas/fixtures/ are the source of truth. This test asserts
 * the generated Java DTOs deserialize them losslessly and serialize back to a JSON document that
 * structurally matches the fixture. The same fixtures get round-tripped through the Python pydantic
 * models in a separate test; together they catch cross-language contract drift.
 */
class RoundTripTest {

  private static final Path FIXTURES = Path.of("../fixtures").toAbsolutePath();

  private final ObjectMapper mapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test
  void copytradeSignalPayload_roundTrips() throws Exception {
    String json = Files.readString(FIXTURES.resolve("copytrade-signal-payload-bto.json"));

    CopytradeSignalPayload deserialized = mapper.readValue(json, CopytradeSignalPayload.class);

    assertThat(deserialized.getSchemaVersion()).isEqualTo(1L);
    assertThat(deserialized.getTenantId()).isEqualTo("dev");
    assertThat(deserialized.getStrategyId()).isEqualTo("copytrade-v1");
    assertThat(deserialized.getSignalId()).isEqualTo("1234567890123456789:0");
    assertThat(deserialized.getAction()).isEqualTo(CopytradeSignalPayload.Action.BTO);
    assertThat(deserialized.getTicker()).isEqualTo("NVDA");
    assertThat(deserialized.getRight()).isEqualTo(CopytradeSignalPayload.Right.C);

    String reserialized = mapper.writeValueAsString(deserialized);
    JsonNode original = mapper.readTree(json);
    JsonNode roundTripped = mapper.readTree(reserialized);

    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void auditEvent_roundTrips() throws Exception {
    String json = Files.readString(FIXTURES.resolve("audit-event.json"));

    AuditEvent deserialized = mapper.readValue(json, AuditEvent.class);

    assertThat(deserialized.getSchemaVersion()).isEqualTo(1L);
    assertThat(deserialized.getTenantId()).isEqualTo("dev");
    assertThat(deserialized.getKind()).isEqualTo("SignalReceived");

    String reserialized = mapper.writeValueAsString(deserialized);
    JsonNode original = mapper.readTree(json);
    JsonNode roundTripped = mapper.readTree(reserialized);

    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void partialExitRequest_roundTrips() throws Exception {
    String json = Files.readString(FIXTURES.resolve("partial-exit-request.json"));

    PartialExitRequest deserialized = mapper.readValue(json, PartialExitRequest.class);

    assertThat(deserialized.getSchemaVersion()).isEqualTo(1L);
    assertThat(deserialized.getTenantId()).isEqualTo("dev");
    assertThat(deserialized.getStrategyId()).isEqualTo("copytrade-v1");
    assertThat(deserialized.getFraction().doubleValue()).isEqualTo(0.5);
    assertThat(deserialized.getReason()).isEqualTo("stc_signal");

    String reserialized = mapper.writeValueAsString(deserialized);
    JsonNode original = mapper.readTree(json);
    JsonNode roundTripped = mapper.readTree(reserialized);

    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void premiumTick_roundTrips() throws Exception {
    String json = Files.readString(FIXTURES.resolve("premium-tick.json"));

    PremiumTick deserialized = mapper.readValue(json, PremiumTick.class);

    assertThat(deserialized.getSchemaVersion()).isEqualTo(1L);
    assertThat(deserialized.getContractSymbol()).isEqualTo("NVDA  260516C00140000");
    assertThat(deserialized.getPremium().doubleValue()).isEqualTo(2.95);

    String reserialized = mapper.writeValueAsString(deserialized);
    JsonNode original = mapper.readTree(json);
    JsonNode roundTripped = mapper.readTree(reserialized);

    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void armChandelierPayload_roundTrips() throws Exception {
    String json = Files.readString(FIXTURES.resolve("arm-chandelier-payload.json"));

    ArmChandelierPayload deserialized = mapper.readValue(json, ArmChandelierPayload.class);

    assertThat(deserialized.getSchemaVersion()).isEqualTo(1L);
    assertThat(deserialized.getTenantId()).isEqualTo("dev");
    assertThat(deserialized.getPeakPremium().doubleValue()).isEqualTo(2.85);
    assertThat(deserialized.getGivebackPct().doubleValue()).isEqualTo(0.15);

    String reserialized = mapper.writeValueAsString(deserialized);
    JsonNode original = mapper.readTree(json);
    JsonNode roundTripped = mapper.readTree(reserialized);

    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void subscribePremiumRequest_roundTrips() throws Exception {
    String json = Files.readString(FIXTURES.resolve("subscribe-premium-request.json"));

    SubscribePremiumRequest deserialized = mapper.readValue(json, SubscribePremiumRequest.class);

    assertThat(deserialized.getSchemaVersion()).isEqualTo(1L);
    assertThat(deserialized.getContractSymbol()).isEqualTo("NVDA  260516C00140000");

    String reserialized = mapper.writeValueAsString(deserialized);
    JsonNode original = mapper.readTree(json);
    JsonNode roundTripped = mapper.readTree(reserialized);

    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void subscribePremiumResult_roundTrips() throws Exception {
    String json = Files.readString(FIXTURES.resolve("subscribe-premium-result.json"));

    SubscribePremiumResult deserialized = mapper.readValue(json, SubscribePremiumResult.class);

    assertThat(deserialized.getSchemaVersion()).isEqualTo(1L);
    assertThat(deserialized.getSubscriptionId()).isEqualTo("sub-7f3b1d40");
    assertThat(deserialized.getStatus()).isEqualTo(SubscribePremiumResult.Status.SUBSCRIBED);

    String reserialized = mapper.writeValueAsString(deserialized);
    JsonNode original = mapper.readTree(json);
    JsonNode roundTripped = mapper.readTree(reserialized);

    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void preTradeCheckRequest_roundTrips() throws Exception {
    String json = Files.readString(FIXTURES.resolve("pre-trade-check-request.json"));

    PreTradeCheckRequest deserialized = mapper.readValue(json, PreTradeCheckRequest.class);

    assertThat(deserialized.getSchemaVersion()).isEqualTo(1L);
    assertThat(deserialized.getTenantId()).isEqualTo("dev");
    assertThat(deserialized.getStrategyId()).isEqualTo("copytrade-v1");
    assertThat(deserialized.getBrokerTarget()).isEqualTo(PreTradeCheckRequest.BrokerTarget.PAPER);
    assertThat(deserialized.getSide()).isEqualTo(PreTradeCheckRequest.Side.BUY);
    assertThat(deserialized.getQty()).isEqualTo(1L);

    String reserialized = mapper.writeValueAsString(deserialized);
    JsonNode original = mapper.readTree(json);
    JsonNode roundTripped = mapper.readTree(reserialized);

    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void preTradeCheckResult_roundTrips() throws Exception {
    String json = Files.readString(FIXTURES.resolve("pre-trade-check-result.json"));

    PreTradeCheckResult deserialized = mapper.readValue(json, PreTradeCheckResult.class);

    assertThat(deserialized.getSchemaVersion()).isEqualTo(1L);
    assertThat(deserialized.getAllowed()).isTrue();
    assertThat(deserialized.getPdtStatus()).isEqualTo(PreTradeCheckResult.PdtStatus.OK);
    assertThat(deserialized.getMarginSufficient()).isTrue();

    String reserialized = mapper.writeValueAsString(deserialized);
    JsonNode original = mapper.readTree(json);
    JsonNode roundTripped = mapper.readTree(reserialized);

    assertThat(roundTripped).isEqualTo(original);
  }
}
