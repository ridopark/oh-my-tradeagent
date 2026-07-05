package com.ohmytradeagent.apigateway.web;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Residual-cleanup evidence reader: does the append-only {@code audit_log} (orchestrator DB, the
 * same store {@link LiveActivationStateReader} reads) show that a tenant-delete was ACTUALLY
 * ATTEMPTED for this tenant? Mirrors {@link LiveActivationStateReader}'s construction/injection +
 * {@code @ConditionalOnProperty} dark gate.
 *
 * <p><b>Why this exists.</b> {@code cleanupResidual} must not treat "zero {@code strategy_config}
 * rows" alone as proof the tenant was created → P0-gated → torn down. A tenant that was NEVER
 * created ALSO has zero rows: the onboard page's invite step is independent of tenant creation, so
 * an operator can invite a user for a {@code tenant_id} before its {@code strategy_config} exists,
 * leaving dashboard rows + zero config. Deleting those would strip a legitimate pending onboarding.
 * So cleanup additionally requires audit evidence of a prior delete attempt.
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
   * True iff an {@code audit_log} row exists for this tenant with a kind that proves a delete was
   * actually attempted ({@code TenantDeleteRequested} — emitted only after P0–P3 pass — or {@code
   * TenantDeleteStepFailed}). Not scoped by {@code strategy_id}: the config rows are gone, so the
   * check is per-tenant.
   */
  public boolean deleteWasRequested(String tenantId) {
    Record hit =
        dsl.fetchOne(
            "SELECT 1 FROM audit_log "
                + "WHERE tenant_id = ? "
                + "AND kind IN ('TenantDeleteRequested', 'TenantDeleteStepFailed') "
                + "LIMIT 1",
            tenantId);
    return hit != null;
  }
}
