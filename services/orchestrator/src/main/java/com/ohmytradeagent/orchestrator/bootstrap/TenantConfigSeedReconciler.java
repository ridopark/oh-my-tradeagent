package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.orchestrator.platform.TenantConfig;
import com.ohmytradeagent.orchestrator.platform.YamlTenantRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.jooq.DSLContext;
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
 * account-loss-cap-db epic (Phase 1): on Spring start, back-fills the {@code tenant_config} table
 * (V8) from the mounted {@code tenants/} tree. For each tenant directory (walked directly, matching
 * {@link TenantConfigBootstrapper} — not {@code TenantStrategyScanner}, which skips tenants without
 * a {@code strategies/} subdir), INSERTs the YAML {@link TenantConfig}'s two cap columns as a row
 * <b>only if the row is absent</b> (insert-if-absent via {@code ON CONFLICT DO NOTHING}). This is
 * what warms the DB from the live ConfigMap (prod_real's {@code account_daily_loss_pct: 0.40})
 * BEFORE an operator flips {@code tenant.config.source=db}. Mirrors {@link
 * StrategyConfigSeedReconciler}.
 *
 * <p><b>Idempotent and non-destructive.</b> An existing row is left UNTOUCHED — no overwrite, no
 * duplicate. While the DB store is not yet authoritative (YAML stays the active reader by default),
 * re-seeding must not clobber a row a later Phase-3 write path may have already tightened.
 *
 * <p><b>Inert while source=yaml.</b> Seeding writes the table but nothing reads it until an
 * operator opts into {@link com.ohmytradeagent.orchestrator.platform.DbTenantRegistry}. So this
 * runner is a behavior-neutral warm-up, safe to ship well ahead of the read-source cutover.
 *
 * <p>{@code @Profile("!test")} mirrors {@link StrategyConfigSeedReconciler}: this runner touches
 * the DB and walks the tenants tree, neither of which the unit-test profile provides. Ordered
 * {@code Ordered.HIGHEST_PRECEDENCE} for the same reason — the DB store is warm before any
 * downstream validator that may read config via the active registry. It self-constructs its own
 * {@link YamlTenantRegistry} to read the YAML cap: seeding via the active bean would be a
 * DB-from-DB no-op in db-mode.
 */
@Component
@Profile("!test")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantConfigSeedReconciler implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(TenantConfigSeedReconciler.class);

  private static final String SEED_ACTOR = "seed:boot";

  private final Path tenantsDir;
  private final YamlTenantRegistry yamlRegistry;
  private final DSLContext dsl;

  @Autowired
  public TenantConfigSeedReconciler(
      @Value("${orchestrator.tenants-dir:tenants}") String tenantsDir, DSLContext dsl) {
    this.tenantsDir = Path.of(tenantsDir);
    // Build the YAML reader locally rather than injecting it: in db-mode no YamlTenantRegistry bean
    // exists, but the seeder must still read the tenants tree to back-fill the DB store in BOTH
    // modes (mirrors StrategyConfigSeedReconciler's self-construct).
    this.yamlRegistry = new YamlTenantRegistry(tenantsDir);
    this.dsl = dsl;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!Files.exists(tenantsDir)) {
      log.warn("tenants dir {} not found; skipping tenant_config seed", tenantsDir);
      return;
    }
    // Enumerate tenant dirs DIRECTLY (not via TenantStrategyScanner, which only emits tenants that
    // have a strategies/ subdir) so the seeded set == the set TenantConfigBootstrapper validates.
    // Otherwise a tenant whose tenant.yaml carries a cap but has no strategy files would be
    // validated
    // at boot yet never seeded, and its cap would silently go inert at the source=db cutover.
    List<Path> tenantDirs;
    try (Stream<Path> s = Files.list(tenantsDir)) {
      tenantDirs = s.filter(Files::isDirectory).toList();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to list tenants dir " + tenantsDir, e);
    }
    int seeded = 0;
    int alreadyPresent = 0;
    for (Path tenantDir : tenantDirs) {
      if (seedIfAbsent(tenantDir.getFileName().toString())) {
        seeded++;
      } else {
        alreadyPresent++;
      }
    }
    log.info(
        "tenant_config seed reconciler: seeded {} tenants, {} already present (scanned {} tenant dirs from {})",
        seeded,
        alreadyPresent,
        tenantDirs.size(),
        tenantsDir);
  }

  /**
   * INSERT the YAML cap for {@code tenantId} if no row exists. Returns {@code true} if a row was
   * inserted, {@code false} if one already existed (left untouched).
   */
  private boolean seedIfAbsent(String tenantId) {
    TenantConfig config = yamlRegistry.get(tenantId);

    // version/updated_at are omitted — V8 declares them NOT NULL DEFAULT (single source of truth
    // for the seed).
    int inserted =
        dsl.execute(
            "INSERT INTO tenant_config "
                + "(tenant_id, account_daily_loss_threshold, account_daily_loss_pct, updated_by) "
                + "VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (tenant_id) DO NOTHING",
            tenantId,
            config.getAccountDailyLossThreshold(),
            config.getAccountDailyLossPct(),
            SEED_ACTOR);

    return inserted > 0;
  }
}
