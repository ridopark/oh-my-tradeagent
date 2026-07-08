package com.ohmytradeagent.orchestrator.platform;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.orchestrator.activities.AccountLossCapChangedEvents;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import java.math.BigDecimal;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * account-loss-cap-db epic (Phase 3): the tenant-editable, SERVER-ENFORCED tighten-only write path
 * for the account-level daily-loss cap ({@code tenant_config}: {@code
 * account_daily_loss_threshold}/{@code account_daily_loss_pct}). A tenant may only make the cap
 * STRICTER (a lower cap = an earlier halt); a raise, a remove, an add-where-none, an out-of-range
 * value, and a below-floor value are all REJECTED. Mirrors {@link StrategyConfigWriter#update}'s
 * compare-and-set + hash-chain-audit shape.
 *
 * <p><b>Deliberate risk re-classification (vs the per-strategy {@code daily_loss_threshold}).</b>
 * On {@code strategy_config}, {@code daily_loss_threshold} is DANGEROUS / hard-block (must equal
 * stored) because {@code KillSwitchWorkflowImpl.heartbeat()} re-reads it every tick and a runtime
 * change would DISARM the circuit-breaker. Here the account cap is intentionally EXPOSURE /
 * tighten-only: {@code AccountKillSwitchWorkflow} re-reads it every tick too, but only
 * strictly-safer edits (equal-or-lower) are permitted — everything else is rejected. This is the
 * risk-manager sign-off artifact.
 *
 * <p><b>Floor (risk-manager C2).</b> {@code (0,1]} + forbid-0 does NOT cover near-zero, and a
 * near-zero cap is irreversible tenant-side (raising it back is rejected) → a near-instant
 * forced-flatten that bricks the tenant's own real-money account. So a tighten below {@link
 * #MIN_ACCOUNT_DAILY_LOSS_PCT} / {@link #MIN_ACCOUNT_DAILY_LOSS_THRESHOLD_USD} is rejected. These
 * floor values are POLICY (tunable).
 *
 * <p><b>Rejection audit (risk-manager C4).</b> A rejected raise/remove/add or below-floor tighten
 * emits a durable {@code AccountLossCapChanged} audit event ({@code
 * outcome=rejected_tighten_only}/{@code rejected_below_floor}) AND a WARN log, so a tenant
 * repeatedly trying to loosen their own loss cap leaves a compromise/abuse tripwire. The rejection
 * audit is written OUTSIDE the CAS transaction (which never opens on a rejection) so it commits
 * independently of the refused write.
 *
 * <p>Plain {@code @Component} (NOT conditional) so the Activity can autowire it unconditionally; it
 * is inert until the api-gateway {@code /tenant-config} route (dark-gated) drives it.
 */
@Component
public class TenantConfigWriter {

  private static final Logger log = LoggerFactory.getLogger(TenantConfigWriter.class);

  /**
   * The tenant-scoped, tighten-only account-cap columns. This is the SINGLE governance list the
   * read badge mirrors: {@code TenantConfigReader.FIELD_CLASSES} (tenant-dashboard-bff) pins its
   * EXPOSURE set to this constant by comment (the BFF cannot import orchestrator), so the read-only
   * "tighten only" badge can never drift from what this writer actually enforces.
   */
  public static final Set<String> TIGHTEN_ONLY_FIELDS =
      Set.of("account_daily_loss_threshold", "account_daily_loss_pct");

  /**
   * Policy floor for {@code account_daily_loss_pct} (5% of start-of-day equity). Tunable. A tighten
   * below this is rejected (irreversible near-zero cap = self-brick, see class javadoc / C2).
   */
  public static final BigDecimal MIN_ACCOUNT_DAILY_LOSS_PCT = new BigDecimal("0.05");

  /** Policy floor for {@code account_daily_loss_threshold} ($100 absolute). Tunable. */
  public static final BigDecimal MIN_ACCOUNT_DAILY_LOSS_THRESHOLD_USD = new BigDecimal("100");

  private final DSLContext dsl;
  private final AuditActivities audit;

  @Autowired
  public TenantConfigWriter(DSLContext dsl, AuditActivities audit) {
    this.dsl = dsl;
    this.audit = audit;
  }

  /**
   * Compare-and-set update of the stored account cap for {@code tenantId}, gated on {@code
   * expectedVersion} and on the tighten-only + floor rules. Returns the new {@code version} on
   * success.
   *
   * @throws TenantConfigNotFoundException if no {@code tenant_config} row exists for the tenant
   * @throws InvalidConfigException if a proposed value is out of range (pct not in {@code (0,1]},
   *     or a non-positive threshold — 0 is forbidden)
   * @throws DangerousFieldChangeRejected if the write would RAISE, REMOVE, or ADD-where-none a cap
   * @throws BelowFloorRejected if a valid tighten sets a cap below the policy floor
   * @throws OptimisticLockException if {@code expectedVersion} is stale
   */
  public long update(
      String tenantId,
      BigDecimal newThreshold,
      BigDecimal newPct,
      long expectedVersion,
      String actor) {

    // a. Load the current row (plain read, this.dsl). Absent → not-found (tenant-scoped).
    Record row =
        dsl.fetchOne(
            "SELECT account_daily_loss_threshold, account_daily_loss_pct, version "
                + "FROM tenant_config WHERE tenant_id = ?",
            tenantId);
    if (row == null) {
      throw new TenantConfigNotFoundException("tenant_config not found for tenant=" + tenantId);
    }
    BigDecimal storedThreshold = row.get("account_daily_loss_threshold", BigDecimal.class);
    BigDecimal storedPct = row.get("account_daily_loss_pct", BigDecimal.class);
    long storedVersion = row.get("version", Long.class);

    // b. Range validity (a client typo — NOT an abuse tripwire, so no rejection audit). Forbids 0.
    validateRange(tenantId, newThreshold, newPct);

    // c/d. Tighten-only (raise/remove/add = a loosening attempt) then floor (a valid tighten but
    // below the near-zero self-brick floor). Order matters: a raise rejects as TIGHTEN_ONLY before
    // the floor is ever considered. Both are abuse/compromise tripwires → one durable rejection
    // audit
    // (C4) OUTSIDE any transaction, then rethrow the distinctly-typed exception so the activity
    // maps
    // it to the right outcome/status (403 vs 422).
    try {
      requireTightenOnly("account_daily_loss_threshold", storedThreshold, newThreshold);
      requireTightenOnly("account_daily_loss_pct", storedPct, newPct);
      requireAboveFloor(newThreshold, newPct);
    } catch (DangerousFieldChangeRejected | BelowFloorRejected e) {
      String outcome =
          e instanceof BelowFloorRejected
              ? AccountLossCapChangedEvents.OUTCOME_REJECTED_BELOW_FLOOR
              : AccountLossCapChangedEvents.OUTCOME_REJECTED_TIGHTEN_ONLY;
      log.warn(
          "tenant account-cap {} REJECTED tenant={} actor={} attempted(threshold={},pct={}) "
              + "stored(threshold={},pct={}) reason={}",
          outcome,
          tenantId,
          actor,
          newThreshold,
          newPct,
          storedThreshold,
          storedPct,
          e.getMessage());
      audit.log(
          AccountLossCapChangedEvents.rejected(
              tenantId,
              actor,
              outcome,
              storedThreshold,
              storedPct,
              storedVersion,
              newThreshold,
              newPct));
      throw e;
    }

    // e. Compare-and-set UPDATE + honored audit, atomically (last-in-txn audit via the hash-chain
    // writer, mirroring StrategyConfigWriter.update). NEVER touches any kill-switch/reset path — a
    // cap edit only mutates tenant_config; a tripped switch stays tripped (risk-manager C5).
    return dsl.transactionResult(
        cfg -> {
          DSLContext tx = cfg.dsl();
          long newVersion = expectedVersion + 1;
          int updated =
              tx.execute(
                  "UPDATE tenant_config "
                      + "SET account_daily_loss_threshold = ?, account_daily_loss_pct = ?, "
                      + "version = version + 1, updated_at = now(), updated_by = ? "
                      + "WHERE tenant_id = ? AND version = ?",
                  newThreshold,
                  newPct,
                  actor,
                  tenantId,
                  expectedVersion);
          if (updated == 0) {
            // The row provably existed at step a, so zero affected rows means version moved.
            throw new OptimisticLockException(
                "stale expectedVersion="
                    + expectedVersion
                    + " for tenant="
                    + tenantId
                    + " — the stored account_config version moved under a concurrent writer; "
                    + "re-read and retry");
          }

          AuditEvent event =
              AccountLossCapChangedEvents.changed(
                  tenantId,
                  actor,
                  storedThreshold,
                  storedPct,
                  newThreshold,
                  newPct,
                  expectedVersion,
                  newVersion);
          audit.log(event);

          log.info(
              "tenant account-cap tighten committed tenant={} {}→{} actor={} "
                  + "threshold({}→{}) pct({}→{})",
              tenantId,
              expectedVersion,
              newVersion,
              actor,
              storedThreshold,
              newThreshold,
              storedPct,
              newPct);
          return newVersion;
        });
  }

  // --- validation ---

  /** {@code account_daily_loss_pct} in {@code (0,1]}; {@code account_daily_loss_threshold} > 0. */
  private static void validateRange(String tenantId, BigDecimal threshold, BigDecimal pct) {
    if (pct != null && (pct.signum() <= 0 || pct.compareTo(BigDecimal.ONE) > 0)) {
      throw new InvalidConfigException(
          "account_daily_loss_pct must be a fraction in (0,1], got "
              + pct
              + " (tenant="
              + tenantId
              + ")");
    }
    if (threshold != null && threshold.signum() <= 0) {
      throw new InvalidConfigException(
          "account_daily_loss_threshold must be > 0, got "
              + threshold
              + " (tenant="
              + tenantId
              + ")");
    }
  }

  /**
   * Tighten-only rule for a single nullable cap field (risk-manager C1 — do NOT reuse {@code
   * StrategyConfigWriter.requireNotIncreased}, which ALLOWS the stored==null case; for a loss cap
   * adding-where-none is not a tightening):
   *
   * <ul>
   *   <li>both null → no cap, unchanged → ALLOWED
   *   <li>stored null, next non-null → ADD-where-none → REJECTED
   *   <li>stored non-null, next null → REMOVE → REJECTED
   *   <li>both non-null → require {@code next <= stored} (compareTo, scale-insensitive) → else
   *       REJECTED
   * </ul>
   */
  private static void requireTightenOnly(String field, BigDecimal stored, BigDecimal next) {
    if (stored == null && next == null) {
      return;
    }
    if (stored == null) {
      throw new DangerousFieldChangeRejected(
          "account cap "
              + field
              + " may not be ADDED where none existed (stored=null, new="
              + next
              + ") — adding a cap is not a tightening (needs operator provenance)");
    }
    if (next == null) {
      throw new DangerousFieldChangeRejected(
          "account cap "
              + field
              + " may not be REMOVED (stored="
              + stored
              + ", new=null) — dropping a loss cap is not a tightening");
    }
    if (next.compareTo(stored) > 0) {
      throw new DangerousFieldChangeRejected(
          "account cap "
              + field
              + " may not be RAISED (stored="
              + stored
              + ", new="
              + next
              + ") — only an equal-or-lower (stricter) account cap is permitted");
    }
  }

  private static void requireAboveFloor(BigDecimal threshold, BigDecimal pct) {
    if (pct != null && pct.compareTo(MIN_ACCOUNT_DAILY_LOSS_PCT) < 0) {
      throw new BelowFloorRejected(
          "account_daily_loss_pct "
              + pct
              + " is below the policy floor "
              + MIN_ACCOUNT_DAILY_LOSS_PCT
              + " — a near-zero cap is irreversible tenant-side and would self-brick the account");
    }
    if (threshold != null && threshold.compareTo(MIN_ACCOUNT_DAILY_LOSS_THRESHOLD_USD) < 0) {
      throw new BelowFloorRejected(
          "account_daily_loss_threshold "
              + threshold
              + " is below the policy floor "
              + MIN_ACCOUNT_DAILY_LOSS_THRESHOLD_USD
              + " — a near-zero cap is irreversible tenant-side and would self-brick the account");
    }
  }
}
