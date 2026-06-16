package com.ohmytradeagent.orchestrator.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.TenantConfigChangedEvents;
import com.ohmytradeagent.orchestrator.activities.TenantConfigSnapshot;
import com.ohmytradeagent.orchestrator.bootstrap.StrategyConfigInvariants;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * daily_loss_threshold} + {@code notional_cap_pct_of_capital_base} from the {@code strategy_config}
 * table every tick, so a runtime write to those fields would disarm the live loss circuit-breaker
 * on the real-money account. Writes are therefore classified and constrained against the
 * currently-stored row:
 *
 * <ul>
 *   <li><b>IDENTITY</b> ({@code tenant_id}, {@code strategy_id}, {@code schema_version}) — must
 *       equal stored.
 *   <li><b>DANGEROUS / hard-block</b> ({@code broker_target}, {@code broker_account_id}, {@code
 *       daily_loss_threshold}, {@code notional_cap_pct_of_capital_base}) — must equal stored;
 *       deferred to P3. ({@code broker_account_id} routes real orders to a brokerage account.)
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

  @Autowired
  public StrategyConfigWriter(DSLContext dsl, ObjectMapper objectMapper, AuditActivities audit) {
    this.dsl = dsl;
    this.objectMapper = objectMapper;
    this.audit = audit;
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

  // --- B1: standalone validation ---

  private void validate(StrategyConfig cfg, String tenantId, String strategyId) {
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
    try {
      StrategyConfigInvariants.validateLiveRequiredGates(cfg, label);
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

    // DANGEROUS: must equal stored (deferred to P3 dual-control). daily_loss_threshold and
    // notional_cap_pct_of_capital_base are the kill-switch disarm vectors — null AND widened are
    // both rejected here because anything other than an exact match changes them.
    requireDangerousUnchanged("broker_target", stored.getBrokerTarget(), next.getBrokerTarget());
    // P4-c: broker_account_id routes real orders to a specific brokerage account; a runtime change
    // would re-route live orders to a different account. Same DANGEROUS class as broker_target.
    // Objects.equals tolerates null==null (absent on both sides → allowed), rejects null→value.
    requireDangerousUnchanged(
        "broker_account_id", stored.getBrokerAccountId(), next.getBrokerAccountId());
    requireDangerousUnchanged(
        "daily_loss_threshold", stored.getDailyLossThreshold(), next.getDailyLossThreshold());
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
    // VALUE-equality for numeric fields (daily_loss_threshold, notional_cap_pct_of_capital_base):
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
