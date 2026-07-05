package com.ohmytradeagent.apigateway.web;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Residual-cleanup evidence reader: does the append-only {@code audit_log} (orchestrator DB, the
 * same store {@link LiveActivationStateReader} reads) show that a tenant-delete was ACTUALLY
 * ATTEMPTED for this tenant, and if so WHEN was the latest attempt? Mirrors {@link
 * LiveActivationStateReader}'s construction/injection + {@code @ConditionalOnProperty} dark gate.
 *
 * <p><b>Why this exists.</b> {@code cleanupResidual} must not treat "zero {@code strategy_config}
 * rows" alone as proof the tenant was created → P0-gated → torn down. A tenant that was NEVER
 * created ALSO has zero rows: the onboard page's invite step is independent of tenant creation, so
 * an operator can invite a user for a {@code tenant_id} before its {@code strategy_config} exists,
 * leaving dashboard rows + zero config. Deleting those would strip a legitimate pending onboarding.
 * So cleanup additionally requires audit evidence of a prior delete attempt.
 *
 * <p><b>Why the timestamp (not just a boolean).</b> {@code tenant_id} is reusable free-text and the
 * {@code audit_log} is retained forever, so a boolean "was it ever deleted?" is defeated by a
 * REUSED id: delete {@code acme} (its {@code TenantDeleteRequested} lives forever) → re-onboard
 * {@code acme} invite-first → the stale delete-evidence would greenlight deleting the NEW
 * incarnation's invite. The only correct signal is TIME: a genuine residual dashboard row PREDATES
 * the delete, a reused id's new invite POSTDATES it. So this returns the LATEST delete-event
 * instant, which {@code cleanupResidual} compares against the newest dashboard-row instant.
 *
 * <p>{@code TenantDeleteRequested} is emitted at teardown step 1 (see {@link
 * TenantDeleteController#delete}), AFTER all P0–P3 pre-flight passes, so a genuinely
 * partial-deleted tenant ALWAYS has it (and {@code TenantDeleteStepFailed} on the fault that
 * stranded the residual); a never-created tenant never does.
 *
 * <p>Read-only. Dark-gated on {@code operator.tenant-delete.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "operator.tenant-delete.enabled", havingValue = "true")
public class TenantDeleteHistoryReader {

  private final DSLContext dsl;

  public TenantDeleteHistoryReader(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * The instant of the LATEST tenant-delete attempt for this tenant — {@code max(occurred_at)} over
   * {@code audit_log} rows whose kind proves a delete was actually attempted ({@code
   * TenantDeleteRequested} — emitted only after P0–P3 pass — or {@code TenantDeleteStepFailed}) —
   * or {@link Optional#empty()} when there is NO such row (never deleted). Not scoped by {@code
   * strategy_id}: the config rows are gone, so the check is per-tenant.
   */
  public Optional<Instant> latestDeleteAt(String tenantId) {
    Record r =
        dsl.fetchOne(
            "SELECT max(occurred_at) AS latest FROM audit_log "
                + "WHERE tenant_id = ? "
                + "AND kind IN ('TenantDeleteRequested', 'TenantDeleteStepFailed')",
            tenantId);
    // A pure aggregate (max) always returns one row; its value is NULL when no delete-event
    // matched.
    Timestamp ts = r == null ? null : r.get("latest", Timestamp.class);
    return ts == null ? Optional.empty() : Optional.of(ts.toInstant());
  }
}
