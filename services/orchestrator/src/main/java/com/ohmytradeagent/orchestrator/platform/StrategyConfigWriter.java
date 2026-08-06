package com.ohmytradeagent.orchestrator.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.TenantConfigChangedEvents;
import com.ohmytradeagent.orchestrator.activities.TenantConfigSnapshot;
import com.ohmytradeagent.orchestrator.bootstrap.StrategyConfigInvariants;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Entry seam for the P0c-a runtime config write path; wired into the tenant-config API in Part B.
 * Tests are the sole caller in P0c-a. A runtime write may only reduce-or-hold risk; risk-increasing
 * or dangerous changes are rejected and deferred to P3 dual-control.
 *
 * <p>The safety model is non-negotiable: {@code KillSwitchWorkflowImpl.heartbeat()} re-reads {@code
 * notional_cap_pct_of_capital_base} from the {@code strategy_config} table every tick, so a runtime
 * write to that field would disarm the live notional circuit-breaker on the real-money account.
 * Writes are therefore classified and constrained against the currently-stored row:
 *
 * <ul>
 *   <li><b>IDENTITY</b> ({@code tenant_id}, {@code strategy_id}, {@code schema_version}) — must
 *       equal stored.
 *   <li><b>DANGEROUS / hard-block</b> ({@code broker_target}, {@code broker_account_id}, {@code
 *       notional_cap_pct_of_capital_base}) — must equal stored; deferred to P3. ({@code
 *       broker_account_id} routes real orders to a brokerage account.) (single-account-loss-rule
 *       Phase 4a: the per-strategy {@code daily_loss_threshold} is a dead field — the account cap
 *       {@code account_daily_loss_pct} is the sole daily-loss breaker — so it is no longer
 *       DANGEROUS; it falls through to SAFE and is freely writable.)
 *   <li><b>EXPOSURE / tighten-only</b> ({@code max_contracts}, {@code min_contracts}, {@code
 *       max_positions}, {@code capital_weight}, {@code max_notional_per_signal}, {@code
 *       max_daily_notional_deployed}) — must not increase vs stored.
 *   <li><b>SAFE</b> — every other field is freely writable.
 * </ul>
 *
 * <p>This is a plain {@code @Component} (NOT conditional) so Part B can autowire it
 * unconditionally. It is inert: no {@code ApplicationRunner}/{@code @PostConstruct}/scheduler calls
 * it in P0c-a.
 */
@Component
public class StrategyConfigWriter {

  private static final Logger log = LoggerFactory.getLogger(StrategyConfigWriter.class);

  static final String SOURCE = "runtime-write";
  // Audit `source` for the Phase I-1b create-tenant INSERT (distinguishes a tenant create from a
  // runtime edit in the audit_log chain — both are TenantConfigChanged, so no new audit kind).
  static final String CREATE_SOURCE = "tenant-create";
  // Audit `source` for the operator tenant-delete teardown (PLAN-2026-07-03). The retained
  // TenantDeleted tombstone is the durable record that a strategy_config row was torn down.
  static final String DELETE_SOURCE = "tenant-delete";
  static final String KIND_TENANT_DELETED = "TenantDeleted";
  // PLAN-2026-08-05-direct-live-tenant-onboarding: the neutral observability kind emitted when a
  // LIVE create arms the tenant's account-level loss cap in-transaction (an INSERT into
  // tenant_config BEFORE the strategy_config INSERT) so the live-required gate passes in one
  // operator action. Registered in AuditEventKinds.ALL_KINDS only (a create-time provisioning
  // event, not a lifecycle/paging kind); the KindRegistryGuardTest enforces the registration.
  static final String KIND_ACCOUNT_CAP_ARMED_ON_CREATE = "AccountCapArmedOnCreate";
  // Audit `source` for the arm-on-create tenant_config INSERT (distinguishes it from the
  // tenant tighten-only write path's `tenant-cap-write`).
  static final String ARM_ON_CREATE_SOURCE = "tenant-create-arm";
  // Dedicated per-tenant account-cap hash-chain sentinel (mirrors AccountLossCapChangedEvents).
  static final String ACCOUNT_CHAIN_STRATEGY_ID = "_account";

  /**
   * Mirrors {@link com.ohmytradeagent.orchestrator.activities.TenantConfigChangedEmitter}'s
   * production redact-set: the {@code StrategyConfig} schema currently has no credential-bearing
   * fields, so this is empty. The constant is the wiring point — when a credential-bearing key
   * lands, edit this set.
   */
  private static final Set<String> REDACTED_KEYS = Set.of();

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;
  private final AuditActivities audit;
  private final TenantRegistry tenantRegistry;

  @Autowired
  public StrategyConfigWriter(
      DSLContext dsl,
      ObjectMapper objectMapper,
      AuditActivities audit,
      TenantRegistry tenantRegistry) {
    this.dsl = dsl;
    this.objectMapper = objectMapper;
    this.audit = audit;
    this.tenantRegistry = tenantRegistry;
  }

  /**
   * Compare-and-set update of the stored {@code StrategyConfig} for {@code (tenantId, strategyId)},
   * gated on {@code expectedVersion} and on the reduce-or-hold-risk field-class rules. Returns the
   * new {@code version} on success.
   *
   * @throws YamlStrategyRegistry.StrategyNotFoundException if no row exists for the key
   * @throws InvalidConfigException if {@code newConfig} is malformed / fails live gates / would
   *     produce a blob the live reader fails-close on
   * @throws DangerousFieldChangeRejected if the write would increase or remove risk
   * @throws OptimisticLockException if {@code expectedVersion} is stale
   */
  public long update(
      String tenantId,
      String strategyId,
      StrategyConfig newConfig,
      long expectedVersion,
      String actor) {
    return dsl.transactionResult(
        cfg -> {
          DSLContext tx = cfg.dsl();

          // a. Load the current row. Absent → not-found (same type as the registries).
          Record row =
              tx.fetchOne(
                  "SELECT schema_version, config::text AS config_text "
                      + "FROM strategy_config WHERE tenant_id = ? AND strategy_id = ?",
                  tenantId,
                  strategyId);
          if (row == null) {
            throw new YamlStrategyRegistry.StrategyNotFoundException(
                "Strategy config not found in DB for tenant="
                    + tenantId
                    + " strategy="
                    + strategyId);
          }
          String storedJson = row.get("config_text", String.class);
          StrategyConfig stored;
          try {
            stored = objectMapper.readValue(storedJson, StrategyConfig.class);
          } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(
                "Failed to deserialize stored strategy_config.config for tenant="
                    + tenantId
                    + " strategy="
                    + strategyId,
                e);
          }

          // b. Validate the proposed config standalone (B1).
          validate(newConfig, tenantId, strategyId);

          // c. Field-class checks vs the stored row.
          checkFieldClasses(stored, newConfig);

          // d. Compare-and-set UPDATE (single atomic statement, no FOR UPDATE).
          String newJson;
          try {
            newJson = objectMapper.writeValueAsString(newConfig);
          } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Round-trip guard in validate() already proved this serializes; defensive only.
            throw new InvalidConfigException(
                "Failed to serialize newConfig for tenant=" + tenantId + " strategy=" + strategyId,
                e);
          }
          long newVersion = expectedVersion + 1;
          int updated =
              tx.execute(
                  "UPDATE strategy_config "
                      + "SET config = ?::jsonb, schema_version = ?, version = version + 1, "
                      + "updated_at = now(), updated_by = ? "
                      + "WHERE tenant_id = ? AND strategy_id = ? AND version = ?",
                  newJson,
                  newConfig.getSchemaVersion(),
                  actor,
                  tenantId,
                  strategyId,
                  expectedVersion);
          if (updated == 0) {
            // The row provably existed at step a, so zero affected rows means version moved.
            throw new OptimisticLockException(
                "stale expectedVersion="
                    + expectedVersion
                    + " for tenant="
                    + tenantId
                    + " strategy="
                    + strategyId
                    + " — the stored version moved under a concurrent writer; re-read and retry");
          }

          // e. Audit (last in-txn call) via the hash-chain writer — never INSERT audit_log
          // directly.
          Map<String, Object> priorMap = TenantConfigSnapshot.canonicalize(objectMapper, stored);
          Map<String, Object> currentMap =
              TenantConfigSnapshot.canonicalize(objectMapper, newConfig);
          AuditEvent event =
              TenantConfigChangedEvents.build(
                  tenantId,
                  strategyId,
                  actor,
                  SOURCE,
                  expectedVersion,
                  newVersion,
                  priorMap,
                  currentMap,
                  REDACTED_KEYS);
          audit.log(event);

          log.info(
              "strategy_config runtime write committed tenant={} strategy={} {}→{} actor={}",
              tenantId,
              strategyId,
              expectedVersion,
              newVersion,
              actor);
          return newVersion;
        });
  }

  /**
   * Phase I-1b create-tenant: INSERT the FIRST {@code strategy_config} row for {@code (tenantId,
   * strategyId)} at version 1. Unlike {@link #update}, there is no stored row and no field-class
   * check — the row does not yet exist, so the only safety is standalone {@link #validate} (which
   * enforces the live-required gates ONLY when {@code broker_target} is live, so a paper create
   * passes and a live create must declare its loss gates). Returns the created {@code version} (1).
   *
   * @throws InvalidConfigException if {@code config} is malformed / fails live gates, or its own
   *     {@code tenant_id}/{@code strategy_id} do not match the create target
   * @throws RowAlreadyExistsException if a row already exists for {@code (tenantId, strategyId)}
   */
  public long create(
      String tenantId,
      String strategyId,
      StrategyConfig config,
      BigDecimal accountDailyLossPct,
      String actor) {
    return dsl.transactionResult(
        cfg -> {
          DSLContext tx = cfg.dsl();

          // a0. Effective account cap: read the tenant's current cap ON THE SAME transaction
          // connection `tx` — NOT via tenantRegistry, whose own DSLContext would not see the
          // uncommitted arm INSERT below (THE key correctness point). Absent row ⇒ no cap.
          Record capRow =
              tx.fetchOne(
                  "SELECT account_daily_loss_pct, account_daily_loss_threshold "
                      + "FROM tenant_config WHERE tenant_id = ?",
                  tenantId);
          BigDecimal effectivePct =
              capRow == null ? null : capRow.get("account_daily_loss_pct", BigDecimal.class);
          BigDecimal effectiveThreshold =
              capRow == null ? null : capRow.get("account_daily_loss_threshold", BigDecimal.class);
          boolean existingCapArmed =
              (effectivePct != null && effectivePct.signum() > 0)
                  || (effectiveThreshold != null && effectiveThreshold.signum() > 0);

          // a1. A LIVE strategy whose tenant has NO armed cap arms one now, in-txn, from the
          // operator-supplied pct — so the live-required gate (validate step a2) passes in a single
          // operator action. Idempotent: a 2nd live strategy on an already-armed tenant has
          // existingCapArmed=true and skips this (ON CONFLICT DO NOTHING also leaves the row).
          if (StrategyConfigInvariants.isLive(config) && !existingCapArmed) {
            if (accountDailyLossPct == null
                || accountDailyLossPct.signum() <= 0
                || accountDailyLossPct.compareTo(BigDecimal.ONE) > 0
                || accountDailyLossPct.compareTo(TenantConfigWriter.MIN_ACCOUNT_DAILY_LOSS_PCT)
                    < 0) {
              throw new InvalidConfigException(
                  "live create requires account_daily_loss_pct >= "
                      + TenantConfigWriter.MIN_ACCOUNT_DAILY_LOSS_PCT
                      + " (a fraction in ["
                      + TenantConfigWriter.MIN_ACCOUNT_DAILY_LOSS_PCT
                      + ",1]) to arm the tenant's account-level loss cap — the sole daily-loss"
                      + " breaker for a real-money strategy (tenant="
                      + tenantId
                      + ", got "
                      + accountDailyLossPct
                      + ")");
            }
            // version is omitted — tenant_config.version is NOT NULL DEFAULT 1 (V8). ON CONFLICT
            // DO NOTHING keeps the arm idempotent under a concurrent second live create.
            int armedRows =
                tx.execute(
                    "INSERT INTO tenant_config (tenant_id, account_daily_loss_pct, updated_by) "
                        + "VALUES (?, ?, ?) ON CONFLICT (tenant_id) DO NOTHING",
                    tenantId,
                    accountDailyLossPct,
                    actor);
            if (armedRows > 0) {
              // WE armed the row → the live gate uses our cap and the audit records the real arm.
              effectivePct = accountDailyLossPct;
              audit.log(accountCapArmedEvent(tenantId, actor, accountDailyLossPct));
            } else {
              // Lost the race: a concurrent live create armed this tenant first (ON CONFLICT DO
              // NOTHING inserted 0 rows). Re-read the winner's committed cap on `tx` so the live
              // gate validates against the REAL breaker, and emit NO arm audit — we armed nothing.
              Record racedRow =
                  tx.fetchOne(
                      "SELECT account_daily_loss_pct, account_daily_loss_threshold "
                          + "FROM tenant_config WHERE tenant_id = ?",
                      tenantId);
              effectivePct =
                  racedRow == null
                      ? null
                      : racedRow.get("account_daily_loss_pct", BigDecimal.class);
              effectiveThreshold =
                  racedRow == null
                      ? null
                      : racedRow.get("account_daily_loss_threshold", BigDecimal.class);
            }
          }

          // a2. Validate the proposed config standalone (same gate as update's step b), but using
          // the EFFECTIVE (existing-or-just-armed) cap read on `tx` — NOT tenantRegistry.get(),
          // which cannot see the uncommitted arm above.
          validate(config, tenantId, strategyId, effectivePct, effectiveThreshold);

          // b. Identity consistency: the config's own tenant_id/strategy_id must match the PK being
          // created (the update path enforces IDENTITY vs the stored row; for a create the target
          // path is the authority — a mismatch would persist an inconsistent row).
          if (!tenantId.equals(config.getTenantId())
              || !strategyId.equals(config.getStrategyId())) {
            throw new InvalidConfigException(
                "config tenant_id/strategy_id ("
                    + config.getTenantId()
                    + "/"
                    + config.getStrategyId()
                    + ") must match the create target ("
                    + tenantId
                    + "/"
                    + strategyId
                    + ")");
          }

          // c. INSERT ... ON CONFLICT DO NOTHING (mirrors StrategyConfigSeedReconciler). version is
          // omitted — the column is NOT NULL DEFAULT 1. Zero affected rows ⇒ the row already
          // exists.
          String json;
          try {
            json = objectMapper.writeValueAsString(config);
          } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Round-trip guard in validate() already proved this serializes; defensive only.
            throw new InvalidConfigException(
                "Failed to serialize config for tenant=" + tenantId + " strategy=" + strategyId, e);
          }
          int inserted =
              tx.execute(
                  "INSERT INTO strategy_config "
                      + "(tenant_id, strategy_id, schema_version, config, updated_by) "
                      + "VALUES (?, ?, ?, ?::jsonb, ?) "
                      + "ON CONFLICT (tenant_id, strategy_id) DO NOTHING",
                  tenantId,
                  strategyId,
                  config.getSchemaVersion(),
                  json,
                  actor);
          if (inserted == 0) {
            throw new RowAlreadyExistsException(
                "strategy_config already exists for tenant="
                    + tenantId
                    + " strategy="
                    + strategyId
                    + " — create is a no-op (use the update path to change an existing tenant)");
          }
          long createdVersion = 1L;

          // d. Audit (last in-txn call) via the hash-chain writer. source=tenant-create
          // distinguishes
          // a create from a runtime edit; oldVersion=null + empty prior map = "no prior row".
          Map<String, Object> currentMap = TenantConfigSnapshot.canonicalize(objectMapper, config);
          AuditEvent event =
              TenantConfigChangedEvents.build(
                  tenantId,
                  strategyId,
                  actor,
                  CREATE_SOURCE,
                  null,
                  createdVersion,
                  Map.of(),
                  currentMap,
                  REDACTED_KEYS);
          audit.log(event);

          log.info(
              "strategy_config tenant create committed tenant={} strategy={} version={} actor={}",
              tenantId,
              strategyId,
              createdVersion,
              actor);
          return createdVersion;
        });
  }

  /**
   * Operator tenant-delete teardown (PLAN-2026-07-03, Phase 2): DELETE the {@code strategy_config}
   * row for {@code (tenantId, strategyId)} and, in the SAME transaction, append a retained {@code
   * TenantDeleted} tombstone via the hash-chain audit writer. Returns the rows-deleted count.
   *
   * <p><b>Idempotent</b> — mirrors the recon-schedule / kill-switch teardown steps: a delete of an
   * already-absent row deletes 0 rows and is a SUCCESS (no throw), so a retried teardown workflow
   * converges. The tombstone is still written on a 0-row delete: it is the forensic record that a
   * teardown was applied to {@code (tenant, strategy)} (its {@code rows_deleted} carries whether a
   * row was actually present), and audit_log is deliberately NOT deleted with the config.
   *
   * <p>No key material is logged or placed in the subject — the {@code strategy_config} row carries
   * none, and the log line stays at coarse identifiers (tenant, strategy, actor, count).
   */
  public int delete(String tenantId, String strategyId, String actor) {
    return dsl.transactionResult(
        cfg -> {
          DSLContext tx = cfg.dsl();

          // a. DELETE the config row. 0 affected rows (already absent) is a success, not an error —
          // the teardown is idempotent.
          int deleted =
              tx.execute(
                  "DELETE FROM strategy_config WHERE tenant_id = ? AND strategy_id = ?",
                  tenantId,
                  strategyId);

          // b. Audit (last in-txn call) via the hash-chain writer — never INSERT audit_log
          // directly. The TenantDeleted tombstone is the retained record of the teardown; it rides
          // the same (tenant_id, strategy_id) chain the config's create/update events did.
          audit.log(tombstone(tenantId, strategyId, actor, deleted));

          log.info(
              "strategy_config tenant delete committed tenant={} strategy={} rows_deleted={} actor={}",
              tenantId,
              strategyId,
              deleted,
              actor);
          return deleted;
        });
  }

  /**
   * Builds the retained {@code TenantDeleted} tombstone {@link AuditEvent}. Not workflow code (this
   * component is driven from an Activity), so {@code OffsetDateTime.now} / {@code UUID.randomUUID}
   * are permitted here exactly as in {@link TenantConfigChangedEvents}. Carries ZERO key material.
   */
  private AuditEvent tombstone(String tenantId, String strategyId, String actor, int rowsDeleted) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("tenant_id", tenantId);
    subject.put("strategy_id", strategyId);
    subject.put("actor", actor);
    subject.put("source", DELETE_SOURCE);
    subject.put("rows_deleted", rowsDeleted);
    subject.put("loaded_at", now);

    AuditEvent event = new AuditEvent();
    event.setSchemaVersion(1L);
    event.setTenantId(tenantId);
    event.setStrategyId(strategyId);
    event.setEventId(UUID.randomUUID().toString());
    event.setOccurredAt(now);
    event.setKind(KIND_TENANT_DELETED);
    event.setActor(actor);
    event.setCorrelationId(tenantId + "/" + strategyId);
    event.setSubject(subject);
    return event;
  }

  /**
   * Builds the neutral {@code AccountCapArmedOnCreate} observability {@link AuditEvent} for the
   * arm-on-create tenant_config INSERT. Rides the dedicated per-tenant account-cap hash chain
   * ({@code strategy_id="_account"}, correlation {@code <tenant>/_account}) — the same chain
   * AccountLossCapChanged uses — so the arm and later tighten edits share one tenant-scoped ledger.
   * Not workflow code (driven from an Activity), so {@code OffsetDateTime.now}/{@code
   * UUID.randomUUID} are permitted. Carries ZERO key material.
   */
  private AuditEvent accountCapArmedEvent(String tenantId, String actor, BigDecimal pct) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("tenant_id", tenantId);
    subject.put("actor", actor);
    subject.put("source", ARM_ON_CREATE_SOURCE);
    subject.put("account_daily_loss_pct", pct);
    subject.put("loaded_at", now);

    AuditEvent event = new AuditEvent();
    event.setSchemaVersion(1L);
    event.setTenantId(tenantId);
    event.setStrategyId(ACCOUNT_CHAIN_STRATEGY_ID);
    event.setEventId(UUID.randomUUID().toString());
    event.setOccurredAt(now);
    event.setKind(KIND_ACCOUNT_CAP_ARMED_ON_CREATE);
    event.setActor(actor);
    event.setCorrelationId(tenantId + "/" + ACCOUNT_CHAIN_STRATEGY_ID);
    event.setSubject(subject);
    return event;
  }

  // --- B1: standalone validation ---

  /**
   * UPDATE-path validation entry: reads the tenant's account cap via {@link #tenantRegistry} (its
   * own DSLContext is correct here — the update path never arms an uncommitted row in the same
   * transaction) and delegates to the cap-parameterized overload. The CREATE path instead calls the
   * overload directly with the cap it read on the transaction connection (see {@link #create}).
   */
  private void validate(StrategyConfig cfg, String tenantId, String strategyId) {
    TenantConfig tenantConfig = tenantRegistry.get(cfg.getTenantId());
    BigDecimal accountDailyLossPct =
        tenantConfig == null ? null : tenantConfig.getAccountDailyLossPct();
    BigDecimal accountDailyLossThreshold =
        tenantConfig == null ? null : tenantConfig.getAccountDailyLossThreshold();
    validate(cfg, tenantId, strategyId, accountDailyLossPct, accountDailyLossThreshold);
  }

  private void validate(
      StrategyConfig cfg,
      String tenantId,
      String strategyId,
      BigDecimal accountDailyLossPct,
      BigDecimal accountDailyLossThreshold) {
    String label = tenantId + "/" + strategyId;

    // (i) schema_version non-null and within build support.
    Long schemaVersion = cfg.getSchemaVersion();
    if (schemaVersion == null) {
      throw new InvalidConfigException("schema_version is required (" + label + ")");
    }
    if (schemaVersion > DbStrategyRegistry.MAX_SUPPORTED_SCHEMA_VERSION) {
      throw new InvalidConfigException(
          "schema_version "
              + schemaVersion
              + " exceeds build-supported "
              + DbStrategyRegistry.MAX_SUPPORTED_SCHEMA_VERSION
              + " ("
              + label
              + ") — refusing to persist a newer-than-build row");
    }

    // (ii) required-field validity + cross-field min<=max. This list mirrors the JSON-Schema
    // `required` set in strategy-config.json by hand (the generated DTO emits no bean-validation
    // constraints); keep them in sync. The round-trip guard (iv) is the fail-close backstop.
    requireNonNull(cfg.getTenantId(), "tenant_id", label);
    requireNonNull(cfg.getStrategyId(), "strategy_id", label);
    requireNonNull(cfg.getBrokerTarget(), "broker_target", label);
    if (cfg.getAuthorWhitelist() == null || cfg.getAuthorWhitelist().isEmpty()) {
      throw new InvalidConfigException("author_whitelist must be non-empty (" + label + ")");
    }
    requireNonNull(cfg.getMaxSignalAgeBtoSecs(), "max_signal_age_bto_secs", label);
    requireNonNull(cfg.getMaxSignalAgeStcSecs(), "max_signal_age_stc_secs", label);
    requireNonNull(cfg.getMaxPositions(), "max_positions", label);
    requireNonNull(cfg.getCapitalWeight(), "capital_weight", label);
    requireNonNull(cfg.getMinContracts(), "min_contracts", label);
    requireNonNull(cfg.getMaxContracts(), "max_contracts", label);
    if (cfg.getMinContracts() > cfg.getMaxContracts()) {
      throw new InvalidConfigException(
          "min_contracts ("
              + cfg.getMinContracts()
              + ") must be <= max_contracts ("
              + cfg.getMaxContracts()
              + ") ("
              + label
              + ")");
    }

    // (iii) live-required gates: reuse the source-agnostic invariant, rewrap its failure.
    // Phase 3b (single-account-loss-rule): the caller supplies the tenant's effective account cap
    // (UPDATE path reads it via tenantRegistry; CREATE path reads it on the transaction connection
    // and may have just armed it) and threads it into the 4-arg overload, so an armed account cap
    // satisfies the live loss-breaker invariant (per-strategy daily_loss_threshold optional). A
    // null cap (unset / config-absent tenant) makes the overload (correctly) reject a -live
    // strategy with no armed account cap.
    try {
      StrategyConfigInvariants.validateLiveRequiredGates(
          cfg, accountDailyLossPct, accountDailyLossThreshold, label);
    } catch (IllegalStateException e) {
      throw new InvalidConfigException(e.getMessage(), e);
    }

    // (iv) round-trip guard: serialize then deserialize through the SAME path DbStrategyRegistry
    // uses. A write must never persist a blob the live reader would fail-close on.
    try {
      String json = objectMapper.writeValueAsString(cfg);
      objectMapper.readValue(json, StrategyConfig.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new InvalidConfigException(
          "newConfig failed the serialize→deserialize round-trip guard ("
              + label
              + ") — refusing to persist a blob the live reader could not parse",
          e);
    }
  }

  private static void requireNonNull(Object value, String field, String label) {
    if (value == null) {
      throw new InvalidConfigException(field + " is required (" + label + ")");
    }
  }

  // --- field-class checks vs stored ---

  private void checkFieldClasses(StrategyConfig stored, StrategyConfig next) {
    // IDENTITY: must equal stored.
    requireIdentity("tenant_id", stored.getTenantId(), next.getTenantId());
    requireIdentity("strategy_id", stored.getStrategyId(), next.getStrategyId());
    requireIdentity("schema_version", stored.getSchemaVersion(), next.getSchemaVersion());

    // DANGEROUS: must equal stored (deferred to P3 dual-control). notional_cap_pct_of_capital_base
    // is the kill-switch disarm vector — null AND widened are both rejected here because anything
    // other than an exact match changes it. (single-account-loss-rule Phase 4a: the per-strategy
    // daily_loss_threshold is a dead field — the account cap account_daily_loss_pct is the sole
    // daily-loss breaker — so it is NO LONGER DANGEROUS and a write may change/clear it freely.)
    requireDangerousUnchanged("broker_target", stored.getBrokerTarget(), next.getBrokerTarget());
    // P4-c: broker_account_id routes real orders to a specific brokerage account; a runtime change
    // would re-route live orders to a different account. Same DANGEROUS class as broker_target.
    // Objects.equals tolerates null==null (absent on both sides → allowed), rejects null→value.
    requireDangerousUnchanged(
        "broker_account_id", stored.getBrokerAccountId(), next.getBrokerAccountId());
    requireDangerousUnchanged(
        "notional_cap_pct_of_capital_base",
        stored.getNotionalCapPctOfCapitalBase(),
        next.getNotionalCapPctOfCapitalBase());

    // EXPOSURE: must not increase (equal or lower OK).
    requireNotIncreased("max_contracts", stored.getMaxContracts(), next.getMaxContracts());
    requireNotIncreased("min_contracts", stored.getMinContracts(), next.getMinContracts());
    requireNotIncreased("max_positions", stored.getMaxPositions(), next.getMaxPositions());
    requireNotIncreased("capital_weight", stored.getCapitalWeight(), next.getCapitalWeight());
    requireNotIncreased(
        "max_notional_per_signal",
        stored.getMaxNotionalPerSignal(),
        next.getMaxNotionalPerSignal());
    requireNotIncreased(
        "max_daily_notional_deployed",
        stored.getMaxDailyNotionalDeployed(),
        next.getMaxDailyNotionalDeployed());
  }

  private static void requireIdentity(String field, Object stored, Object next) {
    if (!Objects.equals(stored, next)) {
      throw new DangerousFieldChangeRejected(
          "IDENTITY field "
              + field
              + " must equal the stored value (stored="
              + stored
              + ", new="
              + next
              + ") — identity drift is rejected");
    }
  }

  private static void requireDangerousUnchanged(String field, Object stored, Object next) {
    // VALUE-equality for numeric fields (notional_cap_pct_of_capital_base):
    // BigDecimal.equals is scale-sensitive, so a JSON round-trip that drops a trailing zero
    // (2500.00 → 2500) would falsely read as a "change" and reject an otherwise-unchanged write.
    // compareTo == 0 is the correct "unchanged value" test; a real value change still fails it. All
    // other DANGEROUS fields (broker_target, broker_account_id) are strings/null — Objects.equals.
    boolean unchanged =
        (stored instanceof BigDecimal a && next instanceof BigDecimal b)
            ? a.compareTo(b) == 0
            : Objects.equals(stored, next);
    if (!unchanged) {
      throw new DangerousFieldChangeRejected(
          "DANGEROUS field "
              + field
              + " may not change at runtime (stored="
              + stored
              + ", new="
              + next
              + ") — it is a kill-switch / live-routing control; deferred to P3 dual-control");
    }
  }

  /**
   * EXPOSURE-field rule (works for any {@link Comparable} cap type — Long or BigDecimal): no prior
   * cap (stored == null) → never an increase; removing a cap (next == null while stored != null) is
   * conservatively rejected (dropping a cap is not a tightening); otherwise reject when next &gt;
   * stored. Only equal-or-lower exposure is permitted at runtime.
   */
  private static <T extends Comparable<T>> void requireNotIncreased(
      String field, T stored, T next) {
    if (stored == null) {
      return;
    }
    if (next == null) {
      throw new DangerousFieldChangeRejected(
          "EXPOSURE field "
              + field
              + " may not be removed (stored="
              + stored
              + ", new=null) — dropping a cap is not a tightening");
    }
    if (next.compareTo(stored) > 0) {
      throw new DangerousFieldChangeRejected(
          "EXPOSURE field "
              + field
              + " may not increase (stored="
              + stored
              + ", new="
              + next
              + ") — only equal-or-lower exposure is permitted at runtime");
    }
  }
}
