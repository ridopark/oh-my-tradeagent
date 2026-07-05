package com.ohmytradeagent.orchestrator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.YamlStrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.YamlStrategyRegistry.StrategyNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #323 part (b): config-load invariant — a {@code broker_target} is owned by exactly one
 * tenant. Two DISTINCT tenants mapping to the same {@code broker_target} is rejected; multiple
 * strategies of ONE tenant sharing a {@code broker_target} is allowed.
 *
 * <p>P0c-b2: the validator now resolves config through the active {@link StrategyRegistry}. The
 * yaml-mode cases drive a real {@link YamlStrategyRegistry} (regression: identical behavior to the
 * pre-P0c-b2 disk reader); the {@code dbMode*} cases use a Mockito stub.
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

  private void writeStrategy(
      Path tenantsDir, String tenant, String strategy, String brokerTarget, String brokerAccountId)
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
        broker_account_id: %s
        author_whitelist:
          - acme_trader
        max_signal_age_bto_secs: 30
        max_signal_age_stc_secs: 60
        max_positions: 5
        capital_weight: 0.2
        min_contracts: 1
        max_contracts: 5
        """
            .formatted(tenant, strategy, brokerTarget, brokerAccountId));
  }

  private static StrategyRegistry yamlRegistry(Path tenantsDir) {
    return new YamlStrategyRegistry(tenantsDir.toString());
  }

  @Test
  void rejectsTwoDistinctTenantsSharingABrokerTarget(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-paper");
    writeStrategy(tenantsDir, "globex", "copytrade-v1", "alpaca-paper");

    assertThatThrownBy(
            () -> CrossTenantBrokerTargetValidator.validate(tenantsDir, yamlRegistry(tenantsDir)))
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

    assertThatCode(
            () -> CrossTenantBrokerTargetValidator.validate(tenantsDir, yamlRegistry(tenantsDir)))
        .doesNotThrowAnyException();
  }

  @Test
  void allowsDistinctTenantsOnDistinctBrokerTargets(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-paper");
    writeStrategy(tenantsDir, "globex", "copytrade-v1", "alpaca-live");

    assertThatCode(
            () -> CrossTenantBrokerTargetValidator.validate(tenantsDir, yamlRegistry(tenantsDir)))
        .doesNotThrowAnyException();
  }

  @Test
  void noOpWhenTenantsDirMissing(@TempDir Path parent) {
    Path missing = parent.resolve("nope");
    StrategyRegistry registry = mock(StrategyRegistry.class);
    assertThatCode(() -> CrossTenantBrokerTargetValidator.validate(missing, registry))
        .doesNotThrowAnyException();
  }

  @Test
  void detectsConflictAcrossThreeTenantsTwoColliding(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-paper");
    writeStrategy(tenantsDir, "globex", "copytrade-v1", "alpaca-live");
    writeStrategy(tenantsDir, "initech", "copytrade-v1", "alpaca-live");

    assertThatThrownBy(
            () -> CrossTenantBrokerTargetValidator.validate(tenantsDir, yamlRegistry(tenantsDir)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("alpaca-live");
  }

  @Test
  void mapReturnsOwnerPerBrokerTarget(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-paper");
    writeStrategy(tenantsDir, "acme", "copytrade-v2", "alpaca-paper");

    assertThat(
            CrossTenantBrokerTargetValidator.ownerByBrokerTarget(
                tenantsDir, yamlRegistry(tenantsDir)))
        .containsEntry("alpaca-paper", "acme");
  }

  // ---- P4-c-a shared-broker-accounts mode (gated, dark by default) ----

  @Test
  void strictModeUnaffectedByBrokerAccountId(@TempDir Path tenantsDir) throws Exception {
    // Behavior-preserving proof: in strict mode (default) the broker_account_id is ignored — a
    // single tenant with an account set passes identically to one without.
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-live", "847309116");

    assertThat(
            CrossTenantBrokerTargetValidator.ownerByBrokerTarget(
                tenantsDir, yamlRegistry(tenantsDir)))
        .containsEntry("alpaca-live", "acme");
  }

  @Test
  void sharedModeAllowsSingleTenantWithoutAccount(@TempDir Path tenantsDir) throws Exception {
    // Even with the flag ON, a single-tenant broker_target needs no account (the live path today).
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-live");

    assertThatCode(
            () ->
                CrossTenantBrokerTargetValidator.validate(
                    tenantsDir, yamlRegistry(tenantsDir), true))
        .doesNotThrowAnyException();
  }

  @Test
  void sharedModeAllowsDistinctTenantsWithDistinctAccounts(@TempDir Path tenantsDir)
      throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-paper", "111");
    writeStrategy(tenantsDir, "globex", "copytrade-v1", "alpaca-paper", "222");

    assertThatCode(
            () ->
                CrossTenantBrokerTargetValidator.validate(
                    tenantsDir, yamlRegistry(tenantsDir), true))
        .doesNotThrowAnyException();
  }

  @Test
  void sharedModeRejectsDistinctTenantsWithSameAccount(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-paper", "111");
    writeStrategy(tenantsDir, "globex", "copytrade-v1", "alpaca-paper", "111");

    assertThatThrownBy(
            () ->
                CrossTenantBrokerTargetValidator.validate(
                    tenantsDir, yamlRegistry(tenantsDir), true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("alpaca-paper")
        .hasMessageContaining("111");
  }

  @Test
  void sharedModeRejectsDistinctTenantsWhenOneAccountAbsent(@TempDir Path tenantsDir)
      throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-paper", "111");
    writeStrategy(tenantsDir, "globex", "copytrade-v1", "alpaca-paper"); // no account

    assertThatThrownBy(
            () ->
                CrossTenantBrokerTargetValidator.validate(
                    tenantsDir, yamlRegistry(tenantsDir), true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("alpaca-paper")
        .hasMessageContaining("globex");
  }

  @Test
  void sharedModeRejectsLiveTenantWithBlankAccount(@TempDir Path tenantsDir) throws Exception {
    // Fleet enablement Phase 2: a PRESENT-but-blank broker_account_id (whitespace-only) on a live
    // tenant sharing a broker_target must fail closed, exactly like an absent one — trimToNull
    // strips it to null so it cannot prove account isolation. Distinct from
    // sharedModeRejectsDistinctTenantsWhenOneAccountAbsent (field omitted); this pins the
    // whitespace-strip path on the -live target.
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-live", "847309116");
    writeStrategy(tenantsDir, "globex", "copytrade-v1", "alpaca-live", "\"   \"");

    assertThatThrownBy(
            () ->
                CrossTenantBrokerTargetValidator.validate(
                    tenantsDir, yamlRegistry(tenantsDir), true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("alpaca-live")
        .hasMessageContaining("globex");
  }

  @Test
  void sharedModeRejectsOneTenantWithInconsistentAccountAcrossStrategies(@TempDir Path tenantsDir)
      throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-paper", "111");
    writeStrategy(tenantsDir, "acme", "copytrade-v2", "alpaca-paper", "222");

    assertThatThrownBy(
            () ->
                CrossTenantBrokerTargetValidator.validate(
                    tenantsDir, yamlRegistry(tenantsDir), true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("acme")
        .hasMessageContaining("Intra-tenant");
  }

  @Test
  void sharedModeAllowsOneTenantWithConsistentAccountAcrossStrategies(@TempDir Path tenantsDir)
      throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-paper", "111");
    writeStrategy(tenantsDir, "acme", "copytrade-v2", "alpaca-paper", "111");

    assertThatCode(
            () ->
                CrossTenantBrokerTargetValidator.validate(
                    tenantsDir, yamlRegistry(tenantsDir), true))
        .doesNotThrowAnyException();
  }

  // ---- db-mode (registry-driven) fail-closed behavior ----

  /**
   * A scanned strategy whose registry row cannot load throws BEFORE it can be classified — the
   * throw MUST propagate (boot fails closed), never degrade to a skip that could corrupt the
   * ownership map.
   */
  @Test
  void dbModeFailsClosedOnMissingRow(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-paper");
    StrategyRegistry registry = mock(StrategyRegistry.class);
    when(registry.get("acme", "copytrade-v1"))
        .thenThrow(new StrategyNotFoundException("Strategy config not found in DB"));

    assertThatThrownBy(() -> CrossTenantBrokerTargetValidator.validate(tenantsDir, registry))
        .isInstanceOf(StrategyNotFoundException.class);
  }

  @Test
  void dbModeBuildsOwnerMapFromValidRows(@TempDir Path tenantsDir) throws Exception {
    writeStrategy(tenantsDir, "acme", "copytrade-v1", "alpaca-paper");
    writeStrategy(tenantsDir, "globex", "copytrade-v1", "alpaca-live");
    StrategyRegistry registry = mock(StrategyRegistry.class);
    when(registry.get("acme", "copytrade-v1")).thenReturn(configWithTarget("alpaca-paper"));
    when(registry.get("globex", "copytrade-v1")).thenReturn(configWithTarget("alpaca-live"));

    assertThat(CrossTenantBrokerTargetValidator.ownerByBrokerTarget(tenantsDir, registry))
        .containsEntry("alpaca-paper", "acme")
        .containsEntry("alpaca-live", "globex");
  }

  private static StrategyConfig configWithTarget(String brokerTarget) {
    StrategyConfig cfg = new StrategyConfig();
    cfg.setBrokerTarget(StrategyConfig.BrokerTarget.fromValue(brokerTarget));
    return cfg;
  }
}
