package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.AuditEvent;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * account-loss-cap-db epic (Phase 3): shared factory for the {@code AccountLossCapChanged} audit
 * event emitted by {@code TenantConfigWriter}. Two shapes ride the SAME kind + hash chain:
 *
 * <ul>
 *   <li>{@link #changed} — the HONORED tighten committed ({@code outcome=changed}, {@code
 *       old_version}/{@code new_version} populated).
 *   <li>{@link #rejected} — a REJECTED raise/remove/add ({@code outcome=rejected_tighten_only}) or
 *       a below-floor tighten ({@code outcome=rejected_below_floor}). Risk-manager sign-off
 *       condition C4: a tenant repeatedly trying to RAISE/REMOVE their own loss cap leaves a
 *       durable compromise/abuse tripwire, not just a transient 403.
 * </ul>
 *
 * <p>The account cap is TENANT-scoped (spans every strategy on the shared broker account), so this
 * uses the sentinel {@code strategy_id="_account"} and correlation {@code <tenant>/_account} — a
 * dedicated per-tenant hash chain independent of any real strategy's trading chain (mirrors the
 * {@code _broker} credential chain in {@link BrokerCredentialChangedEvents}). Carries ZERO key
 * material.
 *
 * <p>The {@link AuditEvent} returned here MUST be emitted via {@link
 * AuditActivities#log(AuditEvent)} so the {@code AuditLogChainWriter} populates {@code
 * prev_hash}/{@code row_hash}; never INSERT it directly. The {@code KIND_*} constant lives here (an
 * {@code activities/} source the audit-svc {@code KindRegistryGuardTest} scans) and is the single
 * source of the kind literal.
 */
public final class AccountLossCapChangedEvents {

  static final String KIND_ACCOUNT_LOSS_CAP_CHANGED = "AccountLossCapChanged";

  /** Provenance label distinguishing the tenant tighten-only write from operator/seed writes. */
  static final String SOURCE = "tenant-cap-write";

  /** Dedicated per-tenant account-cap hash chain sentinel — never a real strategy id. */
  static final String STRATEGY_ID = "_account";

  public static final String OUTCOME_CHANGED = "changed";
  public static final String OUTCOME_REJECTED_TIGHTEN_ONLY = "rejected_tighten_only";
  public static final String OUTCOME_REJECTED_BELOW_FLOOR = "rejected_below_floor";

  private AccountLossCapChangedEvents() {}

  /** The HONORED tighten: {@code outcome=changed}, with the version bump recorded. */
  public static AuditEvent changed(
      String tenantId,
      String actor,
      BigDecimal priorThreshold,
      BigDecimal priorPct,
      BigDecimal currentThreshold,
      BigDecimal currentPct,
      long oldVersion,
      long newVersion) {
    Map<String, Object> subject = base(tenantId, actor, OUTCOME_CHANGED);
    subject.put("prior", capMap(priorThreshold, priorPct));
    subject.put("current", capMap(currentThreshold, currentPct));
    subject.put("old_version", oldVersion);
    subject.put("new_version", newVersion);
    return event(tenantId, actor, subject);
  }

  /**
   * A REJECTED write: {@code outcome} is {@link #OUTCOME_REJECTED_TIGHTEN_ONLY} or {@link
   * #OUTCOME_REJECTED_BELOW_FLOOR}. Records the stored row (with its version) and the attempted
   * values so the ledger shows exactly what was refused. Nothing was persisted to {@code
   * tenant_config}; this event is the ONLY durable trace of the attempt.
   */
  public static AuditEvent rejected(
      String tenantId,
      String actor,
      String outcome,
      BigDecimal storedThreshold,
      BigDecimal storedPct,
      long storedVersion,
      BigDecimal attemptedThreshold,
      BigDecimal attemptedPct) {
    Map<String, Object> subject = base(tenantId, actor, outcome);
    Map<String, Object> stored = capMap(storedThreshold, storedPct);
    stored.put("version", storedVersion);
    subject.put("stored", stored);
    subject.put("attempted", capMap(attemptedThreshold, attemptedPct));
    return event(tenantId, actor, subject);
  }

  private static Map<String, Object> base(String tenantId, String actor, String outcome) {
    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("tenant_id", tenantId);
    subject.put("actor", actor);
    subject.put("source", SOURCE);
    subject.put("outcome", outcome);
    return subject;
  }

  private static Map<String, Object> capMap(BigDecimal threshold, BigDecimal pct) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("threshold", threshold);
    m.put("pct", pct);
    return m;
  }

  private static AuditEvent event(String tenantId, String actor, Map<String, Object> subject) {
    // Not workflow code (driven from an Activity), so OffsetDateTime.now / UUID.randomUUID are
    // permitted here exactly as in TenantConfigChangedEvents / BrokerCredentialChangedEvents.
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    subject.put("loaded_at", now);

    AuditEvent e = new AuditEvent();
    e.setSchemaVersion(1L);
    e.setTenantId(tenantId);
    e.setStrategyId(STRATEGY_ID);
    e.setEventId(UUID.randomUUID().toString());
    e.setOccurredAt(now);
    e.setKind(KIND_ACCOUNT_LOSS_CAP_CHANGED);
    e.setActor(actor);
    e.setCorrelationId(tenantId + "/" + STRATEGY_ID);
    e.setSubject(subject);
    return e;
  }
}
