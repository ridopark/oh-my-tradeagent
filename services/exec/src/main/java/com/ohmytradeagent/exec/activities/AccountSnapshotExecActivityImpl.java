package com.ohmytradeagent.exec.activities;

import com.ohmytradeagent.contract.AccountSnapshotRequest;
import com.ohmytradeagent.contract.AccountSnapshotResult;
import com.ohmytradeagent.contract.activities.AccountSnapshotActivity;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import org.springframework.stereotype.Component;

/**
 * Account-equity gate impl. Thin wrapper around {@link OptionsBroker#getAccountEquity} so each
 * broker adapter can override the behavior independently (Alpaca calls /v2/account, Tradier uses
 * /v1/accounts/{id}/balances, etc.). Stateless; safe under Temporal Activity retry semantics. The
 * orchestrator's notional-cap gate fails closed on any exception or zero equity, so a broker outage
 * rejects entries rather than allowing them.
 *
 * <p>Equity is account-level (one credential set per exec deployment), so the request carries no
 * tenant/strategy — it is identified solely by the {@code broker_target} that routed the dispatch
 * to this worker's {@code broker-<target>} task queue.
 */
@Component
public class AccountSnapshotExecActivityImpl implements AccountSnapshotActivity {

  private final OptionsBroker broker;

  public AccountSnapshotExecActivityImpl(OptionsBroker broker) {
    this.broker = broker;
  }

  @Override
  public AccountSnapshotResult accountSnapshot(AccountSnapshotRequest request) {
    AccountSnapshotResult result = new AccountSnapshotResult();
    result.setSchemaVersion(1L);
    result.setEquity(broker.getAccountEquity());
    // Issue #323: thread the account cash balance for the notional-cap gate's MTM-stable cost-basis
    // capital base (cash + sum_open_notional). equity is retained for the #317 fail-closed
    // contract.
    result.setCash(broker.getAccountCash());
    return result;
  }
}
