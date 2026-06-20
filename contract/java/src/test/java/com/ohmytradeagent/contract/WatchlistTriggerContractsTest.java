package com.ohmytradeagent.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Phase 0 (Contracts) coverage for the watchlist-trigger strategy DTOs.
 *
 * <p>Asserts the generated POJOs expose the expected accessors and round-trip their fixtures, and
 * that StrategyConfig applies the five new optional fields' schema defaults when they are absent
 * (back-compat for every existing config, including copytrade-v1).
 */
class WatchlistTriggerContractsTest {

  private static final Path FIXTURES = Path.of("../fixtures").toAbsolutePath();

  private final ObjectMapper mapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test
  void watchlistTriggerPayload_exposesAccessors_andRoundTrips() throws Exception {
    String json = Files.readString(FIXTURES.resolve("watchlist-trigger-payload.json"));

    WatchlistTriggerPayload deserialized = mapper.readValue(json, WatchlistTriggerPayload.class);

    assertThat(deserialized.getSchemaVersion()).isEqualTo(1L);
    assertThat(deserialized.getTenantId()).isEqualTo("dev");
    assertThat(deserialized.getStrategyId()).isEqualTo("watchlist-trigger-v1");
    assertThat(deserialized.getTicker()).isEqualTo("AAPL");
    assertThat(deserialized.getDirection()).isEqualTo(WatchlistTriggerPayload.Direction.ABOVE);
    assertThat(deserialized.getTrigger()).isEqualByComparingTo(new BigDecimal("195.5"));
    assertThat(deserialized.getStrike()).isEqualByComparingTo(new BigDecimal("200.0"));
    assertThat(deserialized.getRight()).isEqualTo(WatchlistTriggerPayload.Right.C);
    assertThat(deserialized.getAction()).isEqualTo(WatchlistTriggerPayload.Action.BTO);
    assertThat(deserialized.getEtDate().toString()).isEqualTo("2026-06-03");
    assertThat(deserialized.getSourceMessageId()).isEqualTo("1234567890123456789");

    JsonNode original = mapper.readTree(json);
    JsonNode roundTripped = mapper.readTree(mapper.writeValueAsString(deserialized));
    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void armDecision_exposesAccessors_andRoundTrips() throws Exception {
    String json = Files.readString(FIXTURES.resolve("arm-decision.json"));

    ArmDecision deserialized = mapper.readValue(json, ArmDecision.class);

    assertThat(deserialized.getArm()).isTrue();
    assertThat(deserialized.getSizeMultiplier()).isEqualByComparingTo(new BigDecimal("1.0"));
    assertThat(deserialized.getReason()).isEqualTo("trigger_armed");

    JsonNode original = mapper.readTree(json);
    JsonNode roundTripped = mapper.readTree(mapper.writeValueAsString(deserialized));
    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void fireDecision_exposesAccessors_andRoundTrips() throws Exception {
    String json = Files.readString(FIXTURES.resolve("fire-decision.json"));

    FireDecision deserialized = mapper.readValue(json, FireDecision.class);

    assertThat(deserialized.getProceed()).isTrue();
    assertThat(deserialized.getSizeMultiplier()).isEqualByComparingTo(new BigDecimal("0.5"));
    assertThat(deserialized.getReason()).isEqualTo("breakout_confirmed");

    JsonNode original = mapper.readTree(json);
    JsonNode roundTripped = mapper.readTree(mapper.writeValueAsString(deserialized));
    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void armContext_exposesAccessors_andRoundTrips() throws Exception {
    String json = Files.readString(FIXTURES.resolve("arm-context.json"));

    ArmContext deserialized = mapper.readValue(json, ArmContext.class);

    assertThat(deserialized.getEtDate().toString()).isEqualTo("2026-06-03");
    assertThat(deserialized.getCash()).isEqualByComparingTo(new BigDecimal("50000.0"));

    JsonNode original = mapper.readTree(json);
    JsonNode roundTripped = mapper.readTree(mapper.writeValueAsString(deserialized));
    assertThat(roundTripped).isEqualTo(original);
  }

  @Test
  void strategyConfig_absentNewFields_appliesDefaults() throws Exception {
    // A config without any of the five new fields must deserialize to their schema defaults,
    // preserving today's behavior for every existing strategy (e.g. copytrade-v1).
    String json =
        "{\"schema_version\":1,\"tenant_id\":\"dev\",\"strategy_id\":\"copytrade-v1\","
            + "\"broker_target\":\"alpaca-paper\",\"author_whitelist\":[\"a\"],"
            + "\"max_signal_age_bto_secs\":30,\"max_signal_age_stc_secs\":60,\"max_positions\":5,"
            + "\"capital_weight\":0.2,\"min_contracts\":1,\"max_contracts\":5}";

    StrategyConfig deserialized = mapper.readValue(json, StrategyConfig.class);

    assertThat(deserialized.getEntryMode()).isEqualTo(StrategyConfig.EntryMode.BREAKOUT);
    assertThat(deserialized.getWatchlistExpiryRule())
        .isEqualTo(StrategyConfig.WatchlistExpiryRule.NEAREST_WEEKLY);
    assertThat(deserialized.getGapTolerancePct()).isEqualByComparingTo(new BigDecimal("0.005"));
    assertThat(deserialized.getEquityEmitDeltaPct()).isEqualByComparingTo(new BigDecimal("0.0005"));
    assertThat(deserialized.getEnabled()).isTrue();
  }

  @Test
  void strategyConfig_copytradeV1Fixture_deserializesUnchanged_withDefaults() throws Exception {
    // The committed copytrade-v1 fixture carries none of the five new fields; it must still
    // deserialize and surface the new defaults without perturbing any existing value.
    String json = Files.readString(FIXTURES.resolve("strategy-config-copytrade-v1.json"));

    StrategyConfig deserialized = mapper.readValue(json, StrategyConfig.class);

    assertThat(deserialized.getStrategyId()).isEqualTo("copytrade-v1");
    assertThat(deserialized.getCapitalSource()).isEqualTo(StrategyConfig.CapitalSource.STATIC);
    assertThat(deserialized.getEntryMode()).isEqualTo(StrategyConfig.EntryMode.BREAKOUT);
    assertThat(deserialized.getWatchlistExpiryRule())
        .isEqualTo(StrategyConfig.WatchlistExpiryRule.NEAREST_WEEKLY);
    assertThat(deserialized.getGapTolerancePct()).isEqualByComparingTo(new BigDecimal("0.005"));
    assertThat(deserialized.getEquityEmitDeltaPct()).isEqualByComparingTo(new BigDecimal("0.0005"));
    assertThat(deserialized.getEnabled()).isTrue();
  }
}
