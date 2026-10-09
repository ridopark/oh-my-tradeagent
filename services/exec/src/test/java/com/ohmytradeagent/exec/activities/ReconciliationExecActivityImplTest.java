package com.ohmytradeagent.exec.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import com.ohmytradeagent.exec.journal.OrderState;
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

  /**
   * #836 (review catch): the 'recon' attribution was never asserted anywhere. The orchestrator-
   * driven journalReconcileToFilled (#239 stale-entry reconcile) must tag its own mechanism, not
   * inherit a caller's.
   */
  @Test
  void journalReconcileToFilled_tagsReconAttribution() {
    OrderIntentJournal journal = mock(OrderIntentJournal.class);
    ReconciliationExecActivityImpl exec =
        new ReconciliationExecActivityImpl(
            journal, mock(BrokerClientRegistry.class), "alpaca-paper");
    java.time.OffsetDateTime at = java.time.OffsetDateTime.parse("2026-08-25T14:25:48Z");

    exec.journalReconcileToFilled("wf-1:entry", 5L, new java.math.BigDecimal("2.79"), at);

    verify(journal).markFilled("wf-1:entry", 5L, new java.math.BigDecimal("2.79"), at, "recon");
  }

  private static JournaledOrder filledRow(String intentKey, String side) {
    return new JournaledOrder(
        intentKey,
        "sig-" + intentKey,
        "dev",
        "copytrade-v1",
        "alpaca-paper",
        "cid-" + intentKey,
        "AMZN  261002C00265000",
        side,
        3L,
        null,
        OrderState.FILLED,
        "boid-" + intentKey,
        null,
        null,
        null,
        null,
        null,
        3L,
        new java.math.BigDecimal("2.00"),
        null,
        0L);
  }

  // #930: after a partial exit the latest fill is a SELL; the list must also carry the latest
  // BUY (the entry) so adoption anchors on it. Element 0 stays the latest fill (recon's contract).
  @Test
  void journalListFilledByOcc_afterAnExit_appendsTheLatestEntryBuy() {
    OrderIntentJournal journal = mock(OrderIntentJournal.class);
    when(journal.findLatestFilledByOcc("dev", "copytrade-v1", "AMZN261002C00265000"))
        .thenReturn(java.util.Optional.of(filledRow("pos:exit:stc-9", "SELL")));
    when(journal.findLatestFilledByOccAndSide("dev", "copytrade-v1", "AMZN261002C00265000", "BUY"))
        .thenReturn(java.util.Optional.of(filledRow("sig-1:entry", "BUY")));
    ReconciliationExecActivityImpl exec =
        new ReconciliationExecActivityImpl(
            journal, mock(BrokerClientRegistry.class), "alpaca-paper");

    var rows = exec.journalListFilledByOcc("dev", "copytrade-v1", "AMZN261002C00265000");

    assertThat(rows)
        .extracting(r -> r.getIntentKey())
        .containsExactly("pos:exit:stc-9", "sig-1:entry");
  }

  @Test
  void journalListFilledByOcc_latestIsTheEntry_singleRow() {
    OrderIntentJournal journal = mock(OrderIntentJournal.class);
    JournaledOrder buy = filledRow("sig-1:entry", "BUY");
    when(journal.findLatestFilledByOcc(any(), any(), any())).thenReturn(java.util.Optional.of(buy));
    when(journal.findLatestFilledByOccAndSide(any(), any(), any(), eq("BUY")))
        .thenReturn(java.util.Optional.of(buy));
    ReconciliationExecActivityImpl exec =
        new ReconciliationExecActivityImpl(
            journal, mock(BrokerClientRegistry.class), "alpaca-paper");

    assertThat(exec.journalListFilledByOcc("dev", "copytrade-v1", "X")).hasSize(1);
  }

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

  // Fleet enablement Phase 2: under the shared -live exec pod TWO distinct live tenants each recon
  // their OWN broker (keyed on the request tenant), never the ACCOUNT_LEVEL sentinel — so a recon
  // tick for tenant B lists B's open orders, not a pod-wide account view. Locks Phase B under
  // multi-account.
  @Test
  void brokerListOpenOrders_twoDistinctLiveTenantsEachResolveOwnBroker() {
    OptionsBroker brokerA = mock(OptionsBroker.class);
    OptionsBroker brokerB = mock(OptionsBroker.class);
    when(brokerA.listOpenOrders()).thenReturn(List.of());
    when(brokerB.listOpenOrders()).thenReturn(List.of());
    BrokerClientRegistry registry = mock(BrokerClientRegistry.class);
    when(registry.brokerFor(eq("prod_real"), eq("alpaca"))).thenReturn(brokerA);
    when(registry.brokerFor(eq("live_tenant_2"), eq("alpaca"))).thenReturn(brokerB);
    ReconciliationExecActivityImpl exec =
        new ReconciliationExecActivityImpl(mock(OrderIntentJournal.class), registry, "alpaca-live");

    exec.brokerListOpenOrders("prod_real", "copytrade-v1");
    exec.brokerListOpenOrders("live_tenant_2", "copytrade-v1");

    verify(registry).brokerFor("prod_real", "alpaca");
    verify(registry).brokerFor("live_tenant_2", "alpaca");
    verify(registry, never()).brokerFor(eq(BrokerClientRegistry.ACCOUNT_LEVEL), anyString());
    verify(brokerA).listOpenOrders();
    verify(brokerB).listOpenOrders();
  }
}
