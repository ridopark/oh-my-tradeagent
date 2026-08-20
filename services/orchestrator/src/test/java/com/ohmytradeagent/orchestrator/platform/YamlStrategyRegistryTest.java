package com.ohmytradeagent.orchestrator.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ohmytradeagent.contract.StrategyConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlStrategyRegistryTest {

  @Test
  void loadsConfigFromTenantsDir(@TempDir Path tenantsDir) throws Exception {
    Path file = tenantsDir.resolve("dev/strategies/copytrade-v1.yaml");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        schema_version: 1
        tenant_id: dev
        strategy_id: copytrade-v1
        broker_target: paper
        author_whitelist:
          - acme_trader
        max_signal_age_bto_secs: 30
        max_signal_age_stc_secs: 60
        bto_price_move_reject_pct: 0.10
        max_positions: 5
        capital_weight: 0.2
        min_contracts: 1
        max_contracts: 5
        skip_avg: true
        """);

    YamlStrategyRegistry registry = new YamlStrategyRegistry(tenantsDir.toString());
    StrategyConfig cfg = registry.get("dev", "copytrade-v1");

    assertThat(cfg.getAuthorWhitelist()).containsExactly("acme_trader");
    // Issue #3: per-side signal-age defaults.
    assertThat(cfg.getMaxSignalAgeBtoSecs()).isEqualTo(30L);
    assertThat(cfg.getMaxSignalAgeStcSecs()).isEqualTo(60L);
    assertThat(cfg.getBtoPriceMoveRejectPct().compareTo(new java.math.BigDecimal("0.10"))).isZero();
    assertThat(cfg.getMaxPositions()).isEqualTo(5L);
    assertThat(cfg.getCapitalWeight().compareTo(new java.math.BigDecimal("0.2"))).isZero();
    assertThat(cfg.getSkipAvg()).isTrue();
  }

  @Test
  void missingStrategyFile_throwsStrategyNotFound(@TempDir Path tenantsDir) {
    YamlStrategyRegistry registry = new YamlStrategyRegistry(tenantsDir.toString());

    assertThatThrownBy(() -> registry.get("dev", "ghost-strategy"))
        .isInstanceOf(YamlStrategyRegistry.StrategyNotFoundException.class)
        .hasMessageContaining("ghost-strategy");
  }

  @Test
  void loadsPhase3PartialFractions(@TempDir Path tenantsDir) throws Exception {
    Path file = tenantsDir.resolve("dev/strategies/copytrade-v1.yaml");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        schema_version: 1
        tenant_id: dev
        strategy_id: copytrade-v1
        broker_target: paper
        author_whitelist:
          - acme_trader
        max_signal_age_bto_secs: 30
        max_signal_age_stc_secs: 60
        max_positions: 5
        capital_weight: 0.2
        min_contracts: 1
        max_contracts: 5
        default_stc_fraction: 0.5
        partial_fractions:
          out: 1.0
          half: 0.5
          "half out": 0.5
          trim: 0.25
        pending_ttl_paper_secs: 90
        """);

    YamlStrategyRegistry registry = new YamlStrategyRegistry(tenantsDir.toString());
    StrategyConfig cfg = registry.get("dev", "copytrade-v1");

    assertThat(cfg.getDefaultStcFraction().compareTo(new java.math.BigDecimal("0.5"))).isZero();
    assertThat(cfg.getPartialFractions()).containsKeys("out", "half", "half out", "trim");
    assertThat(cfg.getPartialFractions().get("trim"))
        .isEqualByComparingTo(new java.math.BigDecimal("0.25"));
    assertThat(cfg.getPendingTtlPaperSecs()).isEqualTo(90L);
  }

  @Test
  void loadsIssue4SlippageAndRepegFields(@TempDir Path tenantsDir) throws Exception {
    // Issue #4: BTO/STC pricing ladder configuration surface.
    // The three new optional fields (max_slippage_abs, max_slippage_pct,
    // repeg_after_ms) must round-trip through YAML loading without breaking
    // the pre-existing required-field schema. Defaults (omitted) are tested
    // in loadsConfigFromTenantsDir above: the loader must not reject configs
    // that lack these fields.
    Path file = tenantsDir.resolve("dev/strategies/copytrade-v1.yaml");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        schema_version: 1
        tenant_id: dev
        strategy_id: copytrade-v1
        broker_target: paper
        author_whitelist:
          - acme_trader
        max_signal_age_bto_secs: 30
        max_signal_age_stc_secs: 60
        max_positions: 5
        capital_weight: 0.2
        min_contracts: 1
        max_contracts: 5
        max_slippage_abs: 0.05
        max_slippage_pct: 0.03
        repeg_after_ms: 500
        """);

    YamlStrategyRegistry registry = new YamlStrategyRegistry(tenantsDir.toString());
    StrategyConfig cfg = registry.get("dev", "copytrade-v1");

    assertThat(cfg.getMaxSlippageAbs()).isEqualByComparingTo(new java.math.BigDecimal("0.05"));
    assertThat(cfg.getMaxSlippagePct()).isEqualByComparingTo(new java.math.BigDecimal("0.03"));
    assertThat(cfg.getRepegAfterMs()).isEqualTo(500L);
  }

  @Test
  void issue4FieldsAreOptional_omittedConfigLoads(@TempDir Path tenantsDir) throws Exception {
    // Backward-compatibility guard: a pre-Issue-#4 config (none of the three
    // new fields set) must continue to load with all three getters returning
    // null. This is the additive-defaults guarantee from the plan's
    // Done-when #5 guardrail.
    Path file = tenantsDir.resolve("dev/strategies/copytrade-v1.yaml");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        schema_version: 1
        tenant_id: dev
        strategy_id: copytrade-v1
        broker_target: paper
        author_whitelist:
          - acme_trader
        max_signal_age_bto_secs: 30
        max_signal_age_stc_secs: 60
        max_positions: 5
        capital_weight: 0.2
        min_contracts: 1
        max_contracts: 5
        """);

    YamlStrategyRegistry registry = new YamlStrategyRegistry(tenantsDir.toString());
    StrategyConfig cfg = registry.get("dev", "copytrade-v1");

    assertThat(cfg.getMaxSlippageAbs()).isNull();
    assertThat(cfg.getMaxSlippagePct()).isNull();
    assertThat(cfg.getRepegAfterMs()).isNull();
  }

  @Test
  void issue338_rejectsRemovedNotionalCapEquityAlias(@TempDir Path tenantsDir) throws Exception {
    // #338: the deprecated notional_cap_pct_of_equity alias is gone from the schema, and the
    // generated POJO carries no catch-all (jsonschema2pojo includeAdditionalProperties=false), so
    // a config still setting it now FAILS TO LOAD. That loudness is the point: every live tenant
    // was verified migrated before the removal, so a config still carrying the field is a stale
    // config someone needs to see — NOT a value to quietly drop, which would silently leave the
    // notional-cap gate unconfigured (a fail-OPEN on a risk gate).
    //
    // The registry wraps the Jackson failure in IllegalStateException("Failed to parse <path>"),
    // so the field name lives on the CAUSE, not the top-level message.
    Path file = tenantsDir.resolve("dev/strategies/copytrade-v1.yaml");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        schema_version: 1
        tenant_id: dev
        strategy_id: copytrade-v1
        broker_target: paper
        author_whitelist:
          - acme_trader
        max_signal_age_bto_secs: 30
        max_signal_age_stc_secs: 60
        max_positions: 5
        capital_weight: 0.2
        min_contracts: 1
        max_contracts: 5
        notional_cap_pct_of_equity: 0.40
        """);

    YamlStrategyRegistry registry = new YamlStrategyRegistry(tenantsDir.toString());

    assertThatThrownBy(() -> registry.get("dev", "copytrade-v1"))
        .isInstanceOf(IllegalStateException.class)
        .hasStackTraceContaining("notional_cap_pct_of_equity");
  }

  @Test
  void issue336_loadsCanonicalNotionalCapCapitalBaseField(@TempDir Path tenantsDir)
      throws Exception {
    // Issue #336: a config using the canonical notional_cap_pct_of_capital_base field loads.
    Path file = tenantsDir.resolve("dev/strategies/copytrade-v1.yaml");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        schema_version: 1
        tenant_id: dev
        strategy_id: copytrade-v1
        broker_target: paper
        author_whitelist:
          - acme_trader
        max_signal_age_bto_secs: 30
        max_signal_age_stc_secs: 60
        max_positions: 5
        capital_weight: 0.2
        min_contracts: 1
        max_contracts: 5
        notional_cap_pct_of_capital_base: 0.40
        """);

    YamlStrategyRegistry registry = new YamlStrategyRegistry(tenantsDir.toString());
    StrategyConfig cfg = registry.get("dev", "copytrade-v1");

    assertThat(cfg.getNotionalCapPctOfCapitalBase())
        .isEqualByComparingTo(new java.math.BigDecimal("0.40"));
  }
}
