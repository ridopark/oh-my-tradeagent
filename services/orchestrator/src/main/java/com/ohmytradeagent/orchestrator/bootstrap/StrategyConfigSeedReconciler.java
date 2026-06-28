package com.ohmytradeagent.orchestrator.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
import com.ohmytradeagent.orchestrator.platform.YamlStrategyRegistry;
import java.nio.file.Path;
import java.util.List;
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
 * P0a (multi-tenant-broker-credentials epic): on Spring start, back-fills the {@code
 * strategy_config} table (V5) from the mounted {@code tenants/} tree. For each {@code (tenant,
 * strategy)} enumerated by {@link TenantStrategyScanner}, INSERTs the YAML {@link StrategyConfig}
 * as a JSONB row <b>only if the row is absent</b> (insert-if-absent via {@code ON CONFLICT DO
 * NOTHING}).
 *
 * <p><b>Idempotent and non-destructive.</b> An existing row is left UNTOUCHED — content is never
 * overwritten and no duplicate is created. In P0a the DB store is not yet authoritative (YAML stays
 * the active reader), so re-seeding must not clobber any row that a later sub-phase / write path
 * may have already updated.
 *
 * <p>{@code @Profile("!test")} mirrors {@link CrossTenantBrokerTargetBootstrapper}: this runner
 * touches the DB and walks the tenants tree, neither of which the unit-test profile provides.
 *
 * <p>P0c-b2: ordered {@code Ordered.HIGHEST_PRECEDENCE} so the DB store is back-filled BEFORE the
 * {@link LiveRequiredGateBootstrapper} / {@link CrossTenantBrokerTargetBootstrapper} validators run
 * (they read config via the active registry, which in db-mode is the just-seeded store). It keeps
 * self-constructing its own {@link YamlStrategyRegistry} to seed — seeding via the active bean
 * would be a DB-from-DB no-op in db-mode.
 */
@Component
@Profile("!test")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StrategyConfigSeedReconciler implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(StrategyConfigSeedReconciler.class);

  private static final String SEED_ACTOR = "seed:boot";

  private final Path tenantsDir;
  private final YamlStrategyRegistry yamlRegistry;
  private final DSLContext dsl;
  private final ObjectMapper objectMapper;

  @Autowired
  public StrategyConfigSeedReconciler(
      @Value("${orchestrator.tenants-dir:tenants}") String tenantsDir,
      DSLContext dsl,
      ObjectMapper objectMapper) {
    this.tenantsDir = Path.of(tenantsDir);
    // Build the YAML reader locally rather than injecting it: after the registry-bean mutual
    // exclusion (strategy.config.source=db), no YamlStrategyRegistry bean exists in db-mode,
    // but the seeder must still read the tenants tree to back-fill the DB store in BOTH modes.
    // TODO(P0c): when seeder/validators move onto the DB path, split a standalone YAML reader
    // component so this transitional self-construct can go away.
    this.yamlRegistry = new YamlStrategyRegistry(tenantsDir);
    this.dsl = dsl;
    this.objectMapper = objectMapper;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!java.nio.file.Files.exists(tenantsDir)) {
      log.warn("tenants dir {} not found; skipping strategy_config seed", tenantsDir);
      return;
    }
    List<TenantStrategy> entries = TenantStrategyScanner.scan(tenantsDir);
    int seeded = 0;
    int alreadyPresent = 0;
    for (TenantStrategy entry : entries) {
      if (seedIfAbsent(entry.tenantId(), entry.strategyId())) {
        seeded++;
      } else {
        alreadyPresent++;
      }
    }
    log.info(
        "strategy_config seed reconciler: seeded {} strategies, {} already present (scanned {} from {})",
        seeded,
        alreadyPresent,
        entries.size(),
        tenantsDir);
  }

  /**
   * INSERT the YAML config for {@code (tenantId, strategyId)} if no row exists. Returns {@code
   * true} if a row was inserted, {@code false} if one already existed (left untouched).
   */
  private boolean seedIfAbsent(String tenantId, String strategyId) {
    StrategyConfig config = yamlRegistry.get(tenantId, strategyId);
    int schemaVersion =
        config.getSchemaVersion() == null ? 1 : config.getSchemaVersion().intValue();
    String configJson;
    try {
      configJson = objectMapper.writeValueAsString(config);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "Failed to serialize StrategyConfig for tenant=" + tenantId + " strategy=" + strategyId,
          e);
    }

    // version is omitted — V5 declares it NOT NULL DEFAULT 1 (single source of truth for the seed).
    int inserted =
        dsl.execute(
            "INSERT INTO strategy_config "
                + "(tenant_id, strategy_id, schema_version, config, updated_by) "
                + "VALUES (?, ?, ?, ?::jsonb, ?) "
                + "ON CONFLICT (tenant_id, strategy_id) DO NOTHING",
            tenantId,
            strategyId,
            schemaVersion,
            configJson,
            SEED_ACTOR);

    return inserted > 0;
  }
}
