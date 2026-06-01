package com.ohmytradeagent.orchestrator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #323 part (b): config-load invariant — a {@code broker_target} is owned by exactly one
 * tenant. Two DISTINCT tenants mapping to the same {@code broker_target} is rejected; multiple
 * strategies of ONE tenant sharing a {@code broker_target} is allowed.
 */
class CrossTenantBrokerTargetValidatorTest {

  private void writeStrategy(Path tenantsDir, String tenant, String strategy, String brokerTarget)
      throws Exception {
    Path file = tenantsDir.resolve(tenant).resolve("strategies").resolve(strategy + ".yaml");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        schema_version: 1
        tenant_id: %s
        strategy_id: %s
        broker_target: %s
        author_whitelist:
          - acme_trader
        max_signal_age_bto_secs: 30
        max_signal_age_stc_secs: 60
        max_positions: 5
        capital_weight: 0.2
        min_contracts: 1
        max_contracts: 5
        """
            .formatted(tenant, strategy, brokerTarget));
  }

  @Test
  void rejectsTwoDistinctTenantsSharingABrokerTarget(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-paper");
    writeStrategy(tenantsDir, "globex", "copytrade-v1", "alpaca-paper");

    assertThatThrownBy(() -> CrossTenantBrokerTargetValidator.validate(tenantsDir))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("alpaca-paper")
        .hasMessageContaining("acme")
        .hasMessageContaining("globex");
  }

  @Test
  void allowsMultipleStrategiesOfOneTenantSharingABrokerTarget(@TempDir Path tenantsDir)
      throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-paper");
    writeStrategy(tenantsDir, "acme", "copytrade-v2", "alpaca-paper");

    assertThatCode(() -> CrossTenantBrokerTargetValidator.validate(tenantsDir))
        .doesNotThrowAnyException();
  }

  @Test
  void allowsDistinctTenantsOnDistinctBrokerTargets(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-paper");
    writeStrategy(tenantsDir, "globex", "copytrade-v1", "alpaca-live");

    assertThatCode(() -> CrossTenantBrokerTargetValidator.validate(tenantsDir))
        .doesNotThrowAnyException();
  }

  @Test
  void noOpWhenTenantsDirMissing(@TempDir Path parent) {
    Path missing = parent.resolve("nope");
    assertThatCode(() -> CrossTenantBrokerTargetValidator.validate(missing))
        .doesNotThrowAnyException();
  }

  @Test
  void detectsConflictAcrossThreeTenantsTwoColliding(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-paper");
    writeStrategy(tenantsDir, "globex", "copytrade-v1", "alpaca-live");
    writeStrategy(tenantsDir, "initech", "copytrade-v1", "alpaca-live");

    assertThatThrownBy(() -> CrossTenantBrokerTargetValidator.validate(tenantsDir))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("alpaca-live");
  }

  @Test
  void mapReturnsOwnerPerBrokerTarget(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-paper");
    writeStrategy(tenantsDir, "acme", "copytrade-v2", "alpaca-paper");

    assertThat(CrossTenantBrokerTargetValidator.ownerByBrokerTarget(tenantsDir))
        .containsEntry("alpaca-paper", "acme");
  }
}
