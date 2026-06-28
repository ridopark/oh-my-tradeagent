package com.ohmytradeagent.exec.activities;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase B (operator account onboarding): pins the per-tenant broker resolution of {@code
 * brokerListOpenOrders(tenantId, strategyId)}. Under the shared-account path multiple live tenants
 * sit on one broker_target, so the open-orders recon read MUST resolve the request's tenant — not
 * the {@code ACCOUNT_LEVEL} sentinel. Mirrors the resolution-assertion style of {@link
 * AccountSnapshotExecActivityImplTest} (mock the registry, verify the {@code brokerFor} key).
 */
class ReconciliationExecActivityImplTest {

  // P4-a / Phase B: a present tenant_id resolves THAT tenant's broker (keyed on the tenant, not the
  // ACCOUNT_LEVEL sentinel) so recon lists the tenant's own open orders.
  @Test
  void brokerListOpenOrders_resolvesByTenantWhenPresent() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.listOpenOrders()).thenReturn(List.of());
    BrokerClientRegistry registry = mock(BrokerClientRegistry.class);
    when(registry.brokerFor(eq("staging_paper"), eq("alpaca"))).thenReturn(broker);
    ReconciliationExecActivityImpl exec =
        new ReconciliationExecActivityImpl(
            mock(OrderIntentJournal.class), registry, "alpaca-paper");

    exec.brokerListOpenOrders("staging_paper", "copytrade-v1");

    verify(registry).brokerFor("staging_paper", "alpaca");
    verify(broker).listOpenOrders();
  }

  // P4-a / Phase B: a null/blank tenant_id falls back to ACCOUNT_LEVEL — never rejects (would
  // regress the single-account env-fallback path mid-rollout). Mirrors brokerListOpenPositions.
  @Test
  void brokerListOpenOrders_fallsBackToAccountLevelWhenTenantBlank() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.listOpenOrders()).thenReturn(List.of());
    BrokerClientRegistry registry = mock(BrokerClientRegistry.class);
    when(registry.brokerFor(eq(BrokerClientRegistry.ACCOUNT_LEVEL), eq("alpaca")))
        .thenReturn(broker);
    ReconciliationExecActivityImpl exec =
        new ReconciliationExecActivityImpl(
            mock(OrderIntentJournal.class), registry, "alpaca-paper");

    exec.brokerListOpenOrders(null, "copytrade-v1");

    verify(registry).brokerFor(BrokerClientRegistry.ACCOUNT_LEVEL, "alpaca");
  }
}
