package com.ohmytradeagent.tdbff.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlStrategyRegistryTest {

  @TempDir private Path tenantsDir;

  @Test
  void missingStrategyYamlThrowsStrategyNotFound() {
    var registry = new YamlStrategyRegistry(tenantsDir.toString());
    assertThatThrownBy(() -> registry.brokerTarget("dev", "does-not-exist"))
        .isInstanceOf(YamlStrategyRegistry.StrategyNotFoundException.class);
  }

  @Test
  void parsesBrokerTargetFromValidYaml() throws Exception {
    Path strategies = tenantsDir.resolve("dev").resolve("strategies");
    Files.createDirectories(strategies);
    Files.writeString(
        strategies.resolve("copytrade-v1.yaml"),
        """
        schema_version: 1
        tenant_id: dev
        strategy_id: copytrade-v1
        broker_target: alpaca-paper
        """);

    var registry = new YamlStrategyRegistry(tenantsDir.toString());

    assertThat(registry.brokerTarget("dev", "copytrade-v1")).isEqualTo("alpaca-paper");
  }
}
