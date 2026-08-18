package com.ohmytradeagent.contract.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the canonical credential-audit workflow-id shape. UI-P2-a's api-gateway caller and the
 * orchestrator's {@code BrokerCredentialAuditWorkflow} must agree on this literal string (it is the
 * dedup key — the {@code correlation_id} embedded in the id makes a retried api-gateway call
 * collide on {@code REJECT_DUPLICATE} rather than double-audit). It deliberately does NOT route
 * through {@link WorkflowIds#tenantStrategy} (no {@code s-} segment) because a credential is {@code
 * (tenant, provider)}-scoped and strategy-agnostic; the {@code _broker} chain that P6-d committed
 * to is encoded directly here.
 */
class WorkflowIdsTest {

  @Test
  void brokerCredentialAuditIsTheBrokerChainShapeWithoutStrategySegment() {
    assertThat(WorkflowIds.brokerCredentialAudit("acme", "corr-123"))
        .isEqualTo("t-acme/_broker/cred-audit/corr-123");
  }

  /**
   * UI-P3-b: the config-write workflow id IS strategy-scoped (it routes through {@link
   * WorkflowIds#tenantStrategy}, so it carries the {@code s-} segment, unlike the credential audit)
   * and embeds the {@code correlation_id} so a retried api-gateway call dedups on {@code
   * REJECT_DUPLICATE}. api-gateway's caller and the orchestrator's {@code
   * StrategyConfigUpdateWorkflow} must agree on this literal.
   */
  @Test
  void strategyConfigUpdateIsTheTenantStrategyShapeWithCorrelationId() {
    assertThat(WorkflowIds.strategyConfigUpdate("acme", "copytrade-v1", "corr-123"))
        .isEqualTo("t-acme/s-copytrade-v1/cfg-write/corr-123");
  }

  /**
   * Phase I-1b: the create-tenant workflow id is the tenant/strategy shape with a {@code
   * /cfg-create/} segment + correlation id (REJECT_DUPLICATE dedup). api-gateway's caller and the
   * orchestrator's {@code StrategyConfigCreateWorkflow} must agree on this literal.
   */
  @Test
  void strategyConfigCreateIsTheTenantStrategyShapeWithCorrelationId() {
    assertThat(WorkflowIds.strategyConfigCreate("acme", "copytrade-v1", "corr-123"))
        .isEqualTo("t-acme/s-copytrade-v1/cfg-create/corr-123");
  }

  /**
   * PLAN-2026-07-03 Phase 4: the tenant-delete teardown workflow id IS strategy-scoped (routes
   * through {@link WorkflowIds#tenantStrategy}) with a {@code /tenant-delete/} segment +
   * correlation id. api-gateway's {@code TenantDeleteWorkflowClient} and the orchestrator {@code
   * TenantDeleteWorkflow} must agree on this literal.
   */
  @Test
  void tenantDeleteIsTheTenantStrategyShapeWithCorrelationId() {
    assertThat(WorkflowIds.tenantDelete("acme", "copytrade-v1", "corr-123"))
        .isEqualTo("t-acme/s-copytrade-v1/tenant-delete/corr-123");
  }

  /**
   * PLAN-2026-07-03 Phase 4: the audit-emit workflow id is keyed by {@code correlationId} + event
   * {@code kind}, with a caller-supplied {@code uuid} for per-event uniqueness (no {@code
   * tenantStrategy} routing, best-effort audit).
   */
  @Test
  void auditEmitIsCorrelationKindUuidShape() {
    assertThat(WorkflowIds.auditEmit("corr-123", "TenantDeleteRequested", "uuid-abc"))
        .isEqualTo("audit-emit/corr-123/TenantDeleteRequested/uuid-abc");
  }

  /**
   * Phase 6: the account-level kill switch is tenant-scoped (NO {@code s-} segment) — the cap spans
   * every strategy on the tenant's shared broker_target.
   */
  @Test
  void accountKillswitchIsTenantScopedWithoutStrategySegment() {
    assertThat(WorkflowIds.accountKillswitch("acme")).isEqualTo("t-acme/account/killswitch");
  }

  /**
   * Armed-watchlist Redis set key: raw {@code tenant}/{@code strategy} form (no {@code t-}/{@code
   * s-} prefixes) consistent with {@code PositionLookupActivitiesImpl}'s {@code pos:} key. The
   * orchestrator (seed on arm) and the BFF (enumerate the watchlist) both build this literal — a
   * single source so the two cannot drift on the key shape.
   */
  @Test
  void armedWatchlistCacheKeyIsRawTenantStrategyDateForm() {
    assertThat(
            WorkflowIds.armedWatchlistCacheKey(
                "acme", "watchlist-trigger-v1", java.time.LocalDate.of(2026, 6, 24)))
        .isEqualTo("wl-armed:acme:watchlist-trigger-v1:2026-06-24");
  }

  /**
   * #718: the OCC must be recoverable FROM a position workflow id. When recon adopts a position it
   * mints a new workflow id, so an operator's cached id goes dead — but the dead id still names the
   * contract, which is what lets the server find the live owner instead of telling the operator
   * "position_already_closed" about a position that is still open.
   */
  @Test
  void occFromPositionRoundTripsWhatPositionBuilds() {
    String occ = "AMD   260819C00530000"; // padded OCC — embedded spaces are the normal case
    String id = WorkflowIds.position("prod-kipark", "copytrade-v1", occ, "chat-messages-77-15:0");
    assertThat(WorkflowIds.occFromPosition(id)).isEqualTo(occ);
  }

  /** The literal id observed live on 2026-08-17, whose entry signal id carries a trailing ":0". */
  @Test
  void occFromPositionParsesTheProductionId() {
    assertThat(
            WorkflowIds.occFromPosition(
                "t-prod-kipark/s-copytrade-v1/pos/AMD   260819C00530000/"
                    + "chat-messages-769797179992571914-1538925306302439515:0"))
        .isEqualTo("AMD   260819C00530000");
  }

  /**
   * A watchlist entry signal id contains slashes of its own, so the parse must stop at the FIRST
   * separator after the OCC rather than splitting the whole id.
   */
  @Test
  void occFromPositionStopsAtTheOccWhenTheSignalIdContainsSlashes() {
    assertThat(
            WorkflowIds.occFromPosition(
                "t-acme/s-watchlist-trigger-v1/pos/SPY   260817C00500000/wl/2026-08-17/SPY/C"))
        .isEqualTo("SPY   260817C00500000");
  }

  /** Anything that is not a position id yields null — the caller must not guess a contract. */
  @Test
  void occFromPositionRejectsNonPositionIds() {
    assertThat(WorkflowIds.occFromPosition(WorkflowIds.killswitch("acme", "copytrade-v1")))
        .isNull();
    assertThat(WorkflowIds.occFromPosition("t-acme/s-copytrade-v1/pos/")).isNull();
    assertThat(WorkflowIds.occFromPosition("t-acme/s-copytrade-v1/pos/SPY   260817C00500000"))
        .isNull();
    assertThat(WorkflowIds.occFromPosition(null)).isNull();
    assertThat(WorkflowIds.occFromPosition("")).isNull();
  }
}
