package com.ohmytradeagent.orchestrator.activities;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.bootstrap.TenantStrategyScanner;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Issue #88: emits one {@code TenantConfigChanged} audit event per changed {@code (tenant_id,
 * strategy_id)} at orchestrator boot when the per-strategy YAML differs from the previously
 * persisted snapshot.
 *
 * <p>Mirrors the {@code LivePromotionApproved} emit pattern (PR #121): routes exclusively through
 * {@link AuditActivities#log(AuditEvent)} so the {@code AuditLogChainWriter} (PR #117) auto-
 * populates {@code prev_hash}/{@code row_hash}. The new code never touches {@code dsl} or {@code
 * AUDIT_LOG} directly — that would skip the chain writer and produce rows with NULL hashes (halt-
 * condition #1).
 *
 * <p>Runtime ConfigMap rewatch (inotify / Spring {@code @RefreshScope}) is out of scope. K8s
 * ConfigMaps don't hot-reload meaningfully in this codebase; pod restart picks up the new mount.
 * The runbook flow is: edit configmap → rolling restart → capture the audit row this emitter writes
 * on the next boot.
 *
 * <p>Redaction: {@link #REDACTED_KEYS} lists keys whose values carry credentials or Vault paths.
 * Currently empty — the {@code StrategyConfig} schema has no credential-bearing fields. The
 * redaction code path is wired so adding a future key only requires editing the constant. A
 * test-only constructor injects a non-empty set to pin the behavior today.
 *
 * <p>First-boot behavior: when no prior snapshot exists for a {@code (tenant, strategy)}, the
 * emitter records the current state but emits no event — the issue's acceptance criterion 1 covers
 * the first-change case, not the first-ever-boot case.
 */
@Component
@Profile("!test")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantConfigChangedEmitter implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(TenantConfigChangedEmitter.class);

  static final String ACTOR = "orchestrator-svc:configmap-reload";
  static final String SOURCE = "configmap-reload";

  /**
   * Production redact-set is intentionally empty: a current scan of {@code strategy-config.json}
   * shows no credential-bearing or Vault-path fields (all keys are numeric thresholds, boolean
   * gates, whitelists, or the {@code broker_target} enum). The constant is the wiring point — when
   * a future credential-bearing key lands, edit this set; no other code changes needed. The
   * test-only constructor exists to pin the redaction behavior even while production is empty.
   */
  private static final Set<String> REDACTED_KEYS = Set.of();

  private final AuditActivities audit;
  private final StrategyRegistry strategyRegistry;
  private final TenantConfigSnapshot snapshots;
  private final ObjectMapper objectMapper;
  private final Path tenantsDir;
  private final Set<String> redactedKeys;

  // @Autowired disambiguates which constructor Spring should use for DI. The class also exposes a
  // package-private test-only constructor below; with two candidate constructors and no @Autowired,
  // Spring 6 falls back to looking for a no-arg constructor and crashes on boot with
  // BeanInstantiationException → orchestrator CrashLoopBackOff.
  @Autowired
  public TenantConfigChangedEmitter(
      AuditActivities audit,
      StrategyRegistry strategyRegistry,
      ObjectMapper objectMapper,
      @Value("${orchestrator.tenants-dir:tenants}") String tenantsDir,
      @Value("${orchestrator.snapshot-dir:${orchestrator.tenants-dir:tenants}/.snapshot}")
          String snapshotDir) {
    this(
        audit,
        strategyRegistry,
        objectMapper,
        Path.of(tenantsDir),
        new TenantConfigSnapshot(objectMapper, Path.of(snapshotDir)),
        REDACTED_KEYS);
  }

  /**
   * Test-only constructor. Lets unit tests inject a non-empty {@code redactedKeys} set so the
   * redaction behavior is exercised even though production currently has no credential-bearing
   * fields to redact. Also lets tests point at a {@link TenantConfigSnapshot} rooted in a JUnit
   * {@code @TempDir}.
   */
  TenantConfigChangedEmitter(
      AuditActivities audit,
      StrategyRegistry strategyRegistry,
      ObjectMapper objectMapper,
      Path tenantsDir,
      TenantConfigSnapshot snapshots,
      Set<String> redactedKeys) {
    this.audit = audit;
    this.strategyRegistry = strategyRegistry;
    this.objectMapper = objectMapper;
    this.tenantsDir = tenantsDir;
    this.snapshots = snapshots;
    this.redactedKeys = redactedKeys == null ? Set.of() : Set.copyOf(redactedKeys);
  }

  @Override
  public void run(ApplicationArguments args) {
    runOnce();
  }

  /**
   * Exposed for tests so they can drive the bootstrap path without standing up a Spring context.
   * Identical to {@link #run(ApplicationArguments)} but takes no Spring argument.
   */
  void runOnce() {
    if (!Files.exists(tenantsDir)) {
      log.warn("tenants dir {} not found; skipping TenantConfigChanged emit on boot", tenantsDir);
      return;
    }
    for (TenantStrategy ts : TenantStrategyScanner.scan(tenantsDir)) {
      try {
        processOne(ts.tenantId(), ts.strategyId());
      } catch (RuntimeException e) {
        // Per-strategy failure must not block the rest of the scan. The audit-log line above is
        // the forensic backstop; this is the bootstrap-path equivalent of the
        // ReconciliationScheduleBootstrapper's per-strategy try/catch.
        log.error(
            "tenant={} strategy={}: TenantConfigChanged emit failed; continuing scan",
            ts.tenantId(),
            ts.strategyId(),
            e);
      }
    }
  }

  private void processOne(String tenantId, String strategyId) {
    StrategyConfig cfg = strategyRegistry.get(tenantId, strategyId);
    Map<String, Object> current = TenantConfigSnapshot.canonicalize(objectMapper, cfg);
    var priorOpt = snapshots.load(tenantId, strategyId);

    if (priorOpt.isEmpty()) {
      // First boot (or corrupt prior snapshot — see TenantConfigSnapshot.load): record the current
      // state, do not emit. Issue acceptance criterion 1 is "first change", not "first boot".
      writeSnapshotQuietly(tenantId, strategyId, current);
      return;
    }

    Map<String, Object> prior = priorOpt.get();
    if (TenantConfigChangedEvents.diffKeys(prior, current).isEmpty()) {
      // No-op reload (acceptance criterion 2): identical config → no event, snapshot is already
      // current. We do NOT rewrite the file in this branch — it would only add filesystem churn.
      return;
    }

    // Delegate event construction to the shared factory so this boot-time emitter and the P0c-a
    // runtime StrategyConfigWriter emit a byte-identical TenantConfigChanged shape.
    // configmap-reload
    // carries no version columns (null oldVersion/newVersion → keys omitted).
    AuditEvent event =
        TenantConfigChangedEvents.build(
            tenantId, strategyId, ACTOR, SOURCE, null, null, prior, current, redactedKeys);

    audit.log(event);

    writeSnapshotQuietly(tenantId, strategyId, current);
  }

  private void writeSnapshotQuietly(
      String tenantId, String strategyId, Map<String, Object> snapshot) {
    try {
      snapshots.store(tenantId, strategyId, snapshot);
    } catch (IOException e) {
      // A failed snapshot write is non-fatal — next boot will diff against the prior file (or
      // miss it entirely, re-arming a single false-negative). The audit row, if any, is already
      // emitted before this call; losing the snapshot only affects the *next* diff.
      log.warn(
          "tenant={} strategy={}: failed to persist TenantConfig snapshot; next boot may misdiff",
          tenantId,
          strategyId,
          e);
    }
  }
}
