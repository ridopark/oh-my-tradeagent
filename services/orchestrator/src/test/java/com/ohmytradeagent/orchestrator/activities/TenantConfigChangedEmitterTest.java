package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Issue #88 unit tests for {@link TenantConfigChangedEmitter}.
 *
 * <p>Drives the bootstrap path directly with a mock {@link AuditActivities} and a mock {@link
 * StrategyRegistry}. The seven cases below pin the contract enumerated in the plan's "Tests"
 * section:
 *
 * <ol>
 *   <li>first boot emits nothing + writes snapshot
 *   <li>no-op reload emits nothing
 *   <li>single-key change emits exactly one event with both old/new values
 *   <li>multiple-key change on one strategy emits one event with both keys present
 *   <li>multiple-strategy change emits one event per (tenant, strategy)
 *   <li>redacted key emits the key name in changed_keys but omits the value
 *   <li>corrupt prior snapshot is treated as first-boot — no event, file overwritten
 * </ol>
 */
class TenantConfigChangedEmitterTest {

  private ObjectMapper om;
  private StrategyRegistry registry;
  private AuditActivities audit;

  @BeforeEach
  void setUp() {
    om = new ObjectMapper().registerModule(new JavaTimeModule());
    registry = mock(StrategyRegistry.class);
    audit = mock(AuditActivities.class);
  }

  @Test
  void firstBoot_emitsNothing_andWritesSnapshot(@TempDir Path root) throws Exception {
    Path tenantsDir = root.resolve("tenants");
    writeStrategyYaml(tenantsDir, "dev", "copytrade-v1");
    when(registry.get("dev", "copytrade-v1"))
        .thenReturn(
            strategyConfig("dev", "copytrade-v1", StrategyConfig.BrokerTarget.ALPACA_PAPER));

    Path snapshotDir = root.resolve("snapshot");
    TenantConfigSnapshot snapshots = new TenantConfigSnapshot(om, snapshotDir);

    TenantConfigChangedEmitter emitter =
        new TenantConfigChangedEmitter(audit, registry, om, tenantsDir, snapshots, Set.of());
    emitter.runOnce();

    verify(audit, never()).log(any());

    Path snapshotFile = snapshots.path("dev", "copytrade-v1");
    assertThat(snapshotFile).exists();
    Map<String, Object> loaded = snapshots.load("dev", "copytrade-v1").orElseThrow();
    assertThat(loaded).containsEntry("broker_target", "alpaca-paper");
  }

  @Test
  void noOpReload_emitsNothing(@TempDir Path root) throws Exception {
    Path tenantsDir = root.resolve("tenants");
    writeStrategyYaml(tenantsDir, "dev", "copytrade-v1");

    StrategyConfig cfg =
        strategyConfig("dev", "copytrade-v1", StrategyConfig.BrokerTarget.ALPACA_PAPER);
    when(registry.get("dev", "copytrade-v1")).thenReturn(cfg);

    Path snapshotDir = root.resolve("snapshot");
    TenantConfigSnapshot snapshots = new TenantConfigSnapshot(om, snapshotDir);
    // Seed the snapshot with the *same* canonical map the emitter will compute, simulating a prior
    // successful boot.
    snapshots.store("dev", "copytrade-v1", TenantConfigSnapshot.canonicalize(om, cfg));

    TenantConfigChangedEmitter emitter =
        new TenantConfigChangedEmitter(audit, registry, om, tenantsDir, snapshots, Set.of());
    emitter.runOnce();

    verify(audit, never()).log(any());
  }

  @Test
  void singleKeyChange_emitsExactlyOne(@TempDir Path root) throws Exception {
    Path tenantsDir = root.resolve("tenants");
    writeStrategyYaml(tenantsDir, "dev", "copytrade-v1");

    // Prior snapshot says alpaca-paper.
    StrategyConfig prior =
        strategyConfig("dev", "copytrade-v1", StrategyConfig.BrokerTarget.ALPACA_PAPER);
    Path snapshotDir = root.resolve("snapshot");
    TenantConfigSnapshot snapshots = new TenantConfigSnapshot(om, snapshotDir);
    snapshots.store("dev", "copytrade-v1", TenantConfigSnapshot.canonicalize(om, prior));

    // Current is tradier-paper — exactly one key changed.
    StrategyConfig current =
        strategyConfig("dev", "copytrade-v1", StrategyConfig.BrokerTarget.TRADIER_PAPER);
    when(registry.get("dev", "copytrade-v1")).thenReturn(current);

    TenantConfigChangedEmitter emitter =
        new TenantConfigChangedEmitter(audit, registry, om, tenantsDir, snapshots, Set.of());
    emitter.runOnce();

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, times(1)).log(captor.capture());
    AuditEvent event = captor.getValue();
    assertThat(event.getKind()).isEqualTo("TenantConfigChanged");
    assertThat(event.getTenantId()).isEqualTo("dev");
    assertThat(event.getStrategyId()).isEqualTo("copytrade-v1");
    assertThat(event.getCorrelationId()).isEqualTo("dev/copytrade-v1");
    assertThat(event.getActor()).isEqualTo("orchestrator-svc:configmap-reload");

    Map<String, Object> subject = event.getSubject();
    @SuppressWarnings("unchecked")
    List<String> changedKeys = (List<String>) subject.get("changed_keys");
    assertThat(changedKeys).containsExactly("broker_target");

    @SuppressWarnings("unchecked")
    Map<String, Object> oldValues = (Map<String, Object>) subject.get("old_values");
    @SuppressWarnings("unchecked")
    Map<String, Object> newValues = (Map<String, Object>) subject.get("new_values");
    assertThat(oldValues).containsEntry("broker_target", "alpaca-paper");
    assertThat(newValues).containsEntry("broker_target", "tradier-paper");
    assertThat(subject).containsEntry("source", "configmap-reload");
    assertThat(subject).containsKey("loaded_at");
  }

  @Test
  void multipleKeyChange_emitsOnePerStrategy(@TempDir Path root) throws Exception {
    Path tenantsDir = root.resolve("tenants");
    writeStrategyYaml(tenantsDir, "dev", "copytrade-v1");

    StrategyConfig prior =
        strategyConfig("dev", "copytrade-v1", StrategyConfig.BrokerTarget.ALPACA_PAPER);
    prior.setMaxContracts(5L);
    Path snapshotDir = root.resolve("snapshot");
    TenantConfigSnapshot snapshots = new TenantConfigSnapshot(om, snapshotDir);
    snapshots.store("dev", "copytrade-v1", TenantConfigSnapshot.canonicalize(om, prior));

    // Current changes two keys.
    StrategyConfig current =
        strategyConfig("dev", "copytrade-v1", StrategyConfig.BrokerTarget.TRADIER_PAPER);
    current.setMaxContracts(10L);
    when(registry.get("dev", "copytrade-v1")).thenReturn(current);

    TenantConfigChangedEmitter emitter =
        new TenantConfigChangedEmitter(audit, registry, om, tenantsDir, snapshots, Set.of());
    emitter.runOnce();

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, times(1)).log(captor.capture());
    Map<String, Object> subject = captor.getValue().getSubject();
    @SuppressWarnings("unchecked")
    List<String> changedKeys = (List<String>) subject.get("changed_keys");
    assertThat(changedKeys).containsExactlyInAnyOrder("broker_target", "max_contracts");

    @SuppressWarnings("unchecked")
    Map<String, Object> oldValues = (Map<String, Object>) subject.get("old_values");
    @SuppressWarnings("unchecked")
    Map<String, Object> newValues = (Map<String, Object>) subject.get("new_values");
    assertThat(oldValues)
        .containsEntry("broker_target", "alpaca-paper")
        .containsEntry("max_contracts", 5);
    assertThat(newValues)
        .containsEntry("broker_target", "tradier-paper")
        .containsEntry("max_contracts", 10);
  }

  @Test
  void multipleStrategyChange_emitsOnePerTenantStrategy(@TempDir Path root) throws Exception {
    Path tenantsDir = root.resolve("tenants");
    writeStrategyYaml(tenantsDir, "dev", "copytrade-v1");
    writeStrategyYaml(tenantsDir, "dev", "copytrade-v2");

    StrategyConfig priorV1 =
        strategyConfig("dev", "copytrade-v1", StrategyConfig.BrokerTarget.ALPACA_PAPER);
    StrategyConfig priorV2 =
        strategyConfig("dev", "copytrade-v2", StrategyConfig.BrokerTarget.ALPACA_PAPER);
    Path snapshotDir = root.resolve("snapshot");
    TenantConfigSnapshot snapshots = new TenantConfigSnapshot(om, snapshotDir);
    snapshots.store("dev", "copytrade-v1", TenantConfigSnapshot.canonicalize(om, priorV1));
    snapshots.store("dev", "copytrade-v2", TenantConfigSnapshot.canonicalize(om, priorV2));

    StrategyConfig currentV1 =
        strategyConfig("dev", "copytrade-v1", StrategyConfig.BrokerTarget.TRADIER_PAPER);
    StrategyConfig currentV2 =
        strategyConfig("dev", "copytrade-v2", StrategyConfig.BrokerTarget.TRADIER_PAPER);
    when(registry.get("dev", "copytrade-v1")).thenReturn(currentV1);
    when(registry.get("dev", "copytrade-v2")).thenReturn(currentV2);

    TenantConfigChangedEmitter emitter =
        new TenantConfigChangedEmitter(audit, registry, om, tenantsDir, snapshots, Set.of());
    emitter.runOnce();

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, times(2)).log(captor.capture());
    List<AuditEvent> events = captor.getAllValues();
    assertThat(events)
        .extracting(AuditEvent::getStrategyId)
        .containsExactlyInAnyOrder("copytrade-v1", "copytrade-v2");
    assertThat(events).allSatisfy(e -> assertThat(e.getTenantId()).isEqualTo("dev"));
    assertThat(events).allSatisfy(e -> assertThat(e.getKind()).isEqualTo("TenantConfigChanged"));
  }

  @Test
  void redactedKey_emitsKeyButOmitsValue(@TempDir Path root) throws Exception {
    Path tenantsDir = root.resolve("tenants");
    writeStrategyYaml(tenantsDir, "dev", "copytrade-v1");

    StrategyConfig prior =
        strategyConfig("dev", "copytrade-v1", StrategyConfig.BrokerTarget.ALPACA_PAPER);
    Path snapshotDir = root.resolve("snapshot");
    TenantConfigSnapshot snapshots = new TenantConfigSnapshot(om, snapshotDir);
    snapshots.store("dev", "copytrade-v1", TenantConfigSnapshot.canonicalize(om, prior));

    StrategyConfig current =
        strategyConfig("dev", "copytrade-v1", StrategyConfig.BrokerTarget.TRADIER_PAPER);
    when(registry.get("dev", "copytrade-v1")).thenReturn(current);

    // Inject a non-empty redact set via the test-only constructor to pin the behavior.
    TenantConfigChangedEmitter emitter =
        new TenantConfigChangedEmitter(
            audit, registry, om, tenantsDir, snapshots, Set.of("broker_target"));
    emitter.runOnce();

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, times(1)).log(captor.capture());
    Map<String, Object> subject = captor.getValue().getSubject();
    @SuppressWarnings("unchecked")
    List<String> changedKeys = (List<String>) subject.get("changed_keys");
    assertThat(changedKeys).containsExactly("broker_target");

    @SuppressWarnings("unchecked")
    Map<String, Object> oldValues = (Map<String, Object>) subject.get("old_values");
    @SuppressWarnings("unchecked")
    Map<String, Object> newValues = (Map<String, Object>) subject.get("new_values");
    assertThat(oldValues).doesNotContainKey("broker_target");
    assertThat(newValues).doesNotContainKey("broker_target");
  }

  @Test
  void corruptPriorSnapshot_doesNotEmit_andOverwrites(@TempDir Path root) throws Exception {
    Path tenantsDir = root.resolve("tenants");
    writeStrategyYaml(tenantsDir, "dev", "copytrade-v1");

    Path snapshotDir = root.resolve("snapshot");
    TenantConfigSnapshot snapshots = new TenantConfigSnapshot(om, snapshotDir);
    // Write a malformed snapshot file directly to simulate the corrupt-prior-snapshot case.
    Path snapshotFile = snapshots.path("dev", "copytrade-v1");
    Files.createDirectories(snapshotFile.getParent());
    Files.writeString(snapshotFile, "{not json", StandardCharsets.UTF_8);

    StrategyConfig current =
        strategyConfig("dev", "copytrade-v1", StrategyConfig.BrokerTarget.ALPACA_PAPER);
    when(registry.get("dev", "copytrade-v1")).thenReturn(current);

    TenantConfigChangedEmitter emitter =
        new TenantConfigChangedEmitter(audit, registry, om, tenantsDir, snapshots, Set.of());
    emitter.runOnce();

    verify(audit, never()).log(any());
    // File has been overwritten with the canonical current state.
    Map<String, Object> reloaded = snapshots.load("dev", "copytrade-v1").orElseThrow();
    assertThat(reloaded).containsEntry("broker_target", "alpaca-paper");
  }

  private static StrategyConfig strategyConfig(
      String tenantId, String strategyId, StrategyConfig.BrokerTarget brokerTarget) {
    StrategyConfig cfg = new StrategyConfig();
    cfg.setSchemaVersion(1L);
    cfg.setTenantId(tenantId);
    cfg.setStrategyId(strategyId);
    cfg.setBrokerTarget(brokerTarget);
    cfg.setAuthorWhitelist(Set.of("author-1"));
    cfg.setMaxSignalAgeBtoSecs(30L);
    cfg.setMaxSignalAgeStcSecs(60L);
    cfg.setMaxPositions(5L);
    cfg.setCapitalWeight(new BigDecimal("0.1"));
    cfg.setMinContracts(1L);
    cfg.setMaxContracts(5L);
    return cfg;
  }

  private static void writeStrategyYaml(Path tenantsDir, String tenantId, String strategyId)
      throws IOException {
    Path strategies = tenantsDir.resolve(tenantId).resolve("strategies");
    Files.createDirectories(strategies);
    Files.writeString(
        strategies.resolve(strategyId + ".yaml"), "schema_version: 1\n", StandardCharsets.UTF_8);
  }
}
