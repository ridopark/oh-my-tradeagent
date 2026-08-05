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
  void copytradeDeriskPayload_roundTrips() throws Exception {
    String json = Files.readString(FIXTURES.resolve("copytrade-derisk-payload.json"));

    CopytradeDeriskPayload deserialized = mapper.readValue(json, CopytradeDeriskPayload.class);

    assertThat(deserialized.getSchemaVersion()).isEqualTo(1L);
    assertThat(deserialized.getTenantId()).isEqualTo("dev");
    assertThat(deserialized.getStrategyId()).isEqualTo("copytrade-v1");
    assertThat(deserialized.getAuthor()).isEqualTo("TradingTheTrend");
    assertThat(deserialized.getTicker()).isEqualTo("INTC");
    assertThat(deserialized.getRight()).isEqualTo(CopytradeDeriskPayload.Right.C);
    assertThat(deserialized.getTargetBtoSignalId()).isEqualTo("1234567890123456789:0");
    assertThat(deserialized.getTargetEntryPremium().doubleValue()).isEqualTo(1.34);
    assertThat(deserialized.getMatchedCue()).isEqualTo("0 or hero");

    String reserialized = mapper.writeValueAsString(deserialized);
    JsonNode original = mapper.readTree(json);
    JsonNode roundTripped = mapper.readTree(reserialized);

    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void copytradeDeriskPayload_requiredOnly_optionalsAbsent() throws Exception {
    // Attribution can resolve a target without a peak seed or matched-cue label, so the optional
    // target_entry_premium / matched_cue must be omissible and default to null.
    String json =
        "{\"schema_version\":1,\"tenant_id\":\"dev\",\"strategy_id\":\"copytrade-v1\","
            + "\"signal_id\":\"m2:derisk\",\"message_id\":\"m2\",\"author\":\"TradingTheTrend\","
            + "\"posted_at\":\"2026-07-31T17:56:00Z\",\"ticker\":\"INTC\",\"expiry\":\"2026-08-03\","
            + "\"strike\":95.0,\"right\":\"C\",\"target_bto_signal_id\":\"m1:0\","
            + "\"raw_line\":\"0 or hero\"}";

    CopytradeDeriskPayload deserialized = mapper.readValue(json, CopytradeDeriskPayload.class);

    assertThat(deserialized.getTargetEntryPremium()).isNull();
    assertThat(deserialized.getMatchedCue()).isNull();

    String reserialized = mapper.writeValueAsString(deserialized);
    assertThat(mapper.readTree(reserialized)).isEqualTo(mapper.readTree(json));
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
  void getOptionQuoteRequest_roundTrips() throws Exception {
    String json = Files.readString(FIXTURES.resolve("get-option-quote-request.json"));

    GetOptionQuoteRequest deserialized = mapper.readValue(json, GetOptionQuoteRequest.class);

    assertThat(deserialized.getSchemaVersion()).isEqualTo(1L);
    assertThat(deserialized.getTenantId()).isEqualTo("dev");
    assertThat(deserialized.getStrategyId()).isEqualTo("copytrade-v1");
    assertThat(deserialized.getContractSymbol()).isEqualTo("NVDA  260516C00140000");

    String reserialized = mapper.writeValueAsString(deserialized);
    JsonNode original = mapper.readTree(json);
    JsonNode roundTripped = mapper.readTree(reserialized);

    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void optionQuoteResult_roundTrips() throws Exception {
    String json = Files.readString(FIXTURES.resolve("option-quote-result.json"));

    OptionQuoteResult deserialized = mapper.readValue(json, OptionQuoteResult.class);

    assertThat(deserialized.getSchemaVersion()).isEqualTo(1L);
    assertThat(deserialized.getContractSymbol()).isEqualTo("NVDA  260516C00140000");
    assertThat(deserialized.getBid().doubleValue()).isEqualTo(2.90);
    assertThat(deserialized.getMid().doubleValue()).isEqualTo(2.95);
    assertThat(deserialized.getAsk().doubleValue()).isEqualTo(3.00);
    assertThat(deserialized.getStatus()).isEqualTo(OptionQuoteResult.Status.OK);

    String reserialized = mapper.writeValueAsString(deserialized);
    JsonNode original = mapper.readTree(json);
    JsonNode roundTripped = mapper.readTree(reserialized);

    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void watchlistMirrorPayload_roundTrips() throws Exception {
    String json = Files.readString(FIXTURES.resolve("watchlist-mirror-payload.json"));

    WatchlistMirrorPayload deserialized = mapper.readValue(json, WatchlistMirrorPayload.class);

    assertThat(deserialized.getSchemaVersion()).isEqualTo(1L);
    assertThat(deserialized.getTenantId()).isEqualTo("dev");
    assertThat(deserialized.getStrategyId()).isEqualTo("copytrade-v1");
    assertThat(deserialized.getEtDate().toString()).isEqualTo("2026-06-03");
    assertThat(deserialized.getAuthor()).isEqualTo("TradingTheTrend");
    assertThat(deserialized.getSourceMessageId()).isEqualTo("1234567890123456789");

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
  void brokerCredentialAuditRequest_roundTrips() throws Exception {
    String json = Files.readString(FIXTURES.resolve("broker-credential-audit-request.json"));

    BrokerCredentialAuditRequest deserialized =
        mapper.readValue(json, BrokerCredentialAuditRequest.class);

    assertThat(deserialized.getSchemaVersion()).isEqualTo(1L);
    assertThat(deserialized.getTenantId()).isEqualTo("dev");
    assertThat(deserialized.getProvider()).isEqualTo("alpaca");
    assertThat(deserialized.getChangeType())
        .isEqualTo(BrokerCredentialAuditRequest.ChangeType.ROTATE);
    assertThat(deserialized.getOutcome()).isEqualTo(BrokerCredentialAuditRequest.Outcome.SAVED);
    assertThat(deserialized.getBrokerAccountId()).isEqualTo("PA3FKGPFYPLH");
    assertThat(deserialized.getCredentialVersion()).isEqualTo(2L);
    assertThat(deserialized.getKekVersion()).isEqualTo(1L);
    assertThat(deserialized.getCorrelationId()).isEqualTo("req-7f3b1d40");

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

  @Test
  void strategyConfig_roundTrips_andCapitalSourceDefaultsToStatic() throws Exception {
    String json = Files.readString(FIXTURES.resolve("strategy-config-copytrade-v1.json"));

    StrategyConfig deserialized = mapper.readValue(json, StrategyConfig.class);

    assertThat(deserialized.getSchemaVersion()).isEqualTo(1L);
    assertThat(deserialized.getTenantId()).isEqualTo("dev");
    assertThat(deserialized.getStrategyId()).isEqualTo("copytrade-v1");
    // dynamic-account-cash-sizing: the fixture sets capital_source=static explicitly.
    assertThat(deserialized.getCapitalSource()).isEqualTo(StrategyConfig.CapitalSource.STATIC);
    // Phase 0 watchlist-trigger fields: the fixture carries their defaults explicitly.
    assertThat(deserialized.getEntryMode()).isEqualTo(StrategyConfig.EntryMode.BREAKOUT);
    assertThat(deserialized.getEnabled()).isTrue();

    String reserialized = mapper.writeValueAsString(deserialized);
    JsonNode original = mapper.readTree(json);
    JsonNode roundTripped = mapper.readTree(reserialized);

    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void strategyConfig_absentCapitalSource_defaultsToStatic() throws Exception {
    // Back-compat: a config with NO capital_source key deserializes to STATIC (the generated DTO's
    // default-initialized value), preserving today's behavior for every existing strategy.
    String json =
        "{\"schema_version\":1,\"tenant_id\":\"dev\",\"strategy_id\":\"copytrade-v1\","
            + "\"broker_target\":\"alpaca-paper\",\"author_whitelist\":[\"a\"],"
            + "\"max_signal_age_bto_secs\":30,\"max_signal_age_stc_secs\":60,\"max_positions\":5,"
            + "\"capital_weight\":0.2,\"min_contracts\":1,\"max_contracts\":5}";

    StrategyConfig deserialized = mapper.readValue(json, StrategyConfig.class);

    assertThat(deserialized.getCapitalSource()).isEqualTo(StrategyConfig.CapitalSource.STATIC);
  }

  @Test
  void strategyConfig_accountCash_roundTrips() throws Exception {
    String json =
        "{\"schema_version\":1,\"tenant_id\":\"dev\",\"strategy_id\":\"copytrade-v1\","
            + "\"broker_target\":\"alpaca-live\",\"author_whitelist\":[\"a\"],"
            + "\"max_signal_age_bto_secs\":30,\"max_signal_age_stc_secs\":60,\"max_positions\":5,"
            + "\"capital_weight\":0.2,\"capital_source\":\"account_cash\","
            + "\"min_contracts\":1,\"max_contracts\":5}";

    StrategyConfig deserialized = mapper.readValue(json, StrategyConfig.class);
    assertThat(deserialized.getCapitalSource())
        .isEqualTo(StrategyConfig.CapitalSource.ACCOUNT_CASH);

    String reserialized = mapper.writeValueAsString(deserialized);
    assertThat(mapper.readTree(reserialized).get("capital_source").asText())
        .isEqualTo("account_cash");
  }
}
