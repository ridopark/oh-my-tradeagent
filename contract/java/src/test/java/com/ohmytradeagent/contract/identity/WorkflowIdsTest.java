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
}
