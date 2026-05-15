package com.ohmytradeagent.exec.activities;

import com.ohmytradeagent.contract.BrokerOpenOrder;
import com.ohmytradeagent.contract.JournalEntry;
import com.ohmytradeagent.contract.activities.ReconciliationExecActivity;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import com.ohmytradeagent.exec.journal.OrderState;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Phase 5 reconciliation Activity impl for the Tradier paper broker. Adapts the per-service journal
 * + broker port into the cross-service contract types ({@link JournalEntry}, {@link
 * BrokerOpenOrder}). Stateless; safe under Temporal Activity retry semantics.
 */
@Component
public class ReconciliationExecActivityImpl implements ReconciliationExecActivity {

  private final OrderIntentJournal journal;
  private final OptionsBroker broker;

  public ReconciliationExecActivityImpl(OrderIntentJournal journal, OptionsBroker broker) {
    this.journal = journal;
    this.broker = broker;
  }

  @Override
  public List<JournalEntry> journalDumpOpen(String tenantId, String strategyId) {
    return journal.listOpenByTenantStrategy(tenantId, strategyId).stream()
        .map(ReconciliationExecActivityImpl::toContract)
        .toList();
  }

  @Override
  public List<BrokerOpenOrder> brokerListOpenOrders() {
    return broker.listOpenOrders();
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
