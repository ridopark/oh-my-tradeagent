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
}
