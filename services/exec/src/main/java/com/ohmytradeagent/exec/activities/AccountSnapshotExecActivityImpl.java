package com.ohmytradeagent.exec.activities;

import com.ohmytradeagent.contract.AccountSnapshotRequest;
import com.ohmytradeagent.contract.AccountSnapshotResult;
import com.ohmytradeagent.contract.activities.AccountSnapshotActivity;
import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import org.springframework.stereotype.Component;

/**
 * Account-equity gate impl. Thin wrapper around {@link OptionsBroker#getAccount} (equity + cash
 * from a single account read) so each broker adapter can override the behavior independently
 * (Alpaca calls /v2/account once, Tradier uses /v1/accounts/{id}/balances, etc.). Stateless; safe
 * under Temporal Activity retry semantics. The orchestrator's notional-cap gate fails closed on any
 * exception or zero equity, so a broker outage rejects entries rather than allowing them.
 *
 * <p>Equity is account-level (one credential set per exec deployment), so the request carries no
 * tenant/strategy — it is identified solely by the {@code broker_target} that routed the dispatch
 * to this worker's {@code broker-<target>} task queue.
 *
 * <p>P4-a: resolve the broker via {@link BrokerClientRegistry} keyed on {@code (ACCOUNT_LEVEL,
 * provider)}. The request has no tenant, and under the env-fallback source the resolver ignores the
 * tenant key anyway, so behavior is preserved.
 */
@Component
public class AccountSnapshotExecActivityImpl implements AccountSnapshotActivity {

  /**
   * Constant tenant key for account-level resolution. The {@code AccountSnapshotRequest} carries no
   * tenant (account is one credential set per deployment) and the env-fallback resolver ignores the
   * tenant, so a stable constant keeps a single cached client per provider.
   */
  static final String ACCOUNT_LEVEL = "__account_level__";

  private final BrokerClientRegistry brokerRegistry;

  public AccountSnapshotExecActivityImpl(BrokerClientRegistry brokerRegistry) {
    this.brokerRegistry = brokerRegistry;
  }

  @Override
  public AccountSnapshotResult accountSnapshot(AccountSnapshotRequest request) {
    OptionsBroker broker =
        brokerRegistry.brokerFor(
            ACCOUNT_LEVEL, BrokerClientRegistry.providerOf(request.getBrokerTarget().value()));
    AccountSnapshotResult result = new AccountSnapshotResult();
    result.setSchemaVersion(1L);
    // Issue #323: read equity AND cash from a SINGLE broker account fetch (getAccount) rather than
    // two separate getAccountEquity()/getAccountCash() calls — each of which would issue its own
    // uncached /v2/account round-trip. equity is retained for the #317 fail-closed contract; cash
    // feeds the notional-cap gate's MTM-stable cost-basis capital base (cash + sum_open_notional).
    OptionsBroker.AccountSummary account = broker.getAccount();
    result.setEquity(account.equity());
    result.setCash(account.cash());
    // Informational account identity for the tenant dashboard (not used by any gate). Null-safe: a
    // null accountNumber simply leaves the optional field absent.
    result.setAccountNumber(account.accountNumber());
    return result;
  }
}
