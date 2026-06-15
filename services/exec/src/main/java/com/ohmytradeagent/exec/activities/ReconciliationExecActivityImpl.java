package com.ohmytradeagent.exec.activities;

import com.ohmytradeagent.contract.BrokerOpenOrder;
import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.JournalEntry;
import com.ohmytradeagent.contract.activities.ReconciliationExecActivity;
import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import com.ohmytradeagent.exec.journal.OrderState;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase 5 reconciliation Activity impl. Adapts the per-service journal + broker port into the
 * cross-service contract types ({@link JournalEntry}, {@link BrokerOpenOrder}). Stateless; safe
 * under Temporal Activity retry semantics.
 *
 * <p>P4-a: resolve the broker via {@link BrokerClientRegistry}. The recon contract methods carry no
 * {@code broker_target}, so the provider is derived from this single-mode pod's {@code broker.impl}
 * (e.g. {@code alpaca-paper} → {@code alpaca}); the tenant is the request's {@code tenantId} when
 * present, else {@code ACCOUNT_LEVEL}. The env-fallback source ignores the tenant, so behavior is
 * preserved.
 */
@Component
public class ReconciliationExecActivityImpl implements ReconciliationExecActivity {

  private final OrderIntentJournal journal;
  private final BrokerClientRegistry brokerRegistry;
  private final String provider;

  public ReconciliationExecActivityImpl(
      OrderIntentJournal journal,
      BrokerClientRegistry brokerRegistry,
      @Value("${broker.impl:}") String brokerImpl) {
    this.journal = journal;
    this.brokerRegistry = brokerRegistry;
    this.provider = BrokerClientRegistry.providerOf(brokerImpl);
  }

  private OptionsBroker broker(String tenantId) {
    return brokerRegistry.brokerFor(
        tenantId != null ? tenantId : AccountSnapshotExecActivityImpl.ACCOUNT_LEVEL, provider);
  }

  @Override
  public List<JournalEntry> journalDumpOpen(String tenantId, String strategyId) {
    return journal.listOpenByTenantStrategy(tenantId, strategyId).stream()
        .map(ReconciliationExecActivityImpl::toContract)
        .toList();
  }

  @Override
  public List<BrokerOpenOrder> brokerListOpenOrders() {
    return broker(AccountSnapshotExecActivityImpl.ACCOUNT_LEVEL).listOpenOrders();
  }

  @Override
  public List<BrokerPosition> brokerListOpenPositions(String tenantId, String strategyId) {
    // tenantId / strategyId are forward-compat hooks — Alpaca paper is single-account so the
    // broker's listOpenPositions cannot filter by them today. Future multi-account brokers will
    // honour them inside the broker impl.
    return broker(tenantId).listOpenPositions();
  }

  @Override
  public List<JournalEntry> journalListFilledByOcc(String tenantId, String strategyId, String occ) {
    return journal
        .findLatestFilledByOcc(tenantId, strategyId, occ)
        .map(ReconciliationExecActivityImpl::toContract)
        .map(List::of)
        .orElse(List.of());
  }

  /**
   * Issue #239: broker truth for the adoption phantom guard. {@code strategyId} is intentionally
   * unused — the resolved broker client is already tenant/strategy-scoped at construction (per the
   * broker task queue), so it only documents the call site.
   */
  @Override
  public BrokerPosition brokerGetPositionByOcc(String tenantId, String strategyId, String occ) {
    // Issue #239: broker truth for the adoption phantom guard. Filters the broker position list to
    // the requested OCC; returns null when the broker does not hold it.
    return broker(tenantId).listOpenPositions().stream()
        .filter(p -> occ.equals(p.getOptionSymbol()))
        .findFirst()
        .orElse(null);
  }

  @Override
  public boolean journalReconcileToFilled(
      String intentKey,
      long filledQty,
      java.math.BigDecimal avgFillPrice,
      java.time.OffsetDateTime filledAt) {
    // Issue #239: terminalize the stale entry row to FILLED. markFilled is conditional on the
    // current state being in (RECORDED, SUBMITTED), so a repeat call is an idempotent no-op.
    return journal.markFilled(intentKey, filledQty, avgFillPrice, filledAt);
  }

  private static JournalEntry toContract(JournaledOrder row) {
    JournalEntry e = new JournalEntry();
    e.setSchemaVersion(1L);
    e.setIntentKey(row.intentKey());
    e.setSignalId(row.signalId());
    e.setTenantId(row.tenantId());
    e.setStrategyId(row.strategyId());
    e.setBrokerTarget(JournalEntry.BrokerTarget.fromValue(row.brokerTarget()));
    e.setClientOrderId(row.clientOrderId());
    e.setOptionSymbol(row.optionSymbol());
    e.setSide(JournalEntry.Side.fromValue(row.side()));
    e.setQty(row.qty());
    e.setState(toContractState(row.state()));
    e.setBrokerOrderId(row.brokerOrderId());
    e.setRecordedAt(row.recordedAt());
    e.setSubmittedAt(row.submittedAt());
    return e;
  }

  private static JournalEntry.State toContractState(OrderState s) {
    return JournalEntry.State.fromValue(s.name());
  }
}
