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
        max_signal_age_secs: 1800
        max_positions: 5
        capital_weight: 0.2
        min_contracts: 1
        max_contracts: 5
        skip_avg: true
        """);

    YamlStrategyRegistry registry = new YamlStrategyRegistry(tenantsDir.toString());
    StrategyConfig cfg = registry.get("dev", "copytrade-v1");

    assertThat(cfg.getAuthorWhitelist()).containsExactly("acme_trader");
    assertThat(cfg.getMaxSignalAgeSecs()).isEqualTo(1800L);
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
}
