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
 * <p>P4-c-b: resolve the broker via {@link BrokerClientRegistry} keyed on the request's {@code
 * tenant_id} so each tenant's cap-basis cash reads its OWN brokerage account. When {@code
 * tenant_id} is null/blank — the account-level dashboard equity caller, or a legacy request — fall
 * back to {@link BrokerClientRegistry#ACCOUNT_LEVEL}. Under the env-fallback credential source the
 * resolver ignores the tenant key, so both paths resolve the same single account and behavior is
 * preserved until per-tenant file creds are active. This is a READ; the registry's P2
 * account-identity assertion runs at build time on whichever key resolves.
 */
@Component
public class AccountSnapshotExecActivityImpl implements AccountSnapshotActivity {

  private final BrokerClientRegistry brokerRegistry;

  public AccountSnapshotExecActivityImpl(BrokerClientRegistry brokerRegistry) {
    this.brokerRegistry = brokerRegistry;
  }

  @Override
  public AccountSnapshotResult accountSnapshot(AccountSnapshotRequest request) {
    // Per-tenant resolution: a present tenant_id reads that tenant's own account; a null/blank
    // tenant_id (dashboard account-level caller / legacy request) falls back to ACCOUNT_LEVEL.
    // Under env creds both resolve the same single account, so this is behavior-preserving.
    String tenantId = request.getTenantId();
    String resolveKey =
        (tenantId == null || tenantId.isBlank()) ? BrokerClientRegistry.ACCOUNT_LEVEL : tenantId;
    OptionsBroker broker =
        brokerRegistry.brokerFor(
            resolveKey, BrokerClientRegistry.providerOf(request.getBrokerTarget().value()));
    AccountSnapshotResult result = new AccountSnapshotResult();
    result.setSchemaVersion(1L);
    // Issue #323: read equity AND cash from a SINGLE broker account fetch (getAccount) rather than
    // two separate getAccountEquity()/getAccountCash() calls — each of which would issue its own
    // uncached /v2/account round-trip. equity is retained for the #317 fail-closed contract; cash
    // feeds the notional-cap gate's MTM-stable cost-basis capital base (cash + sum_open_notional).
    OptionsBroker.AccountSummary account = broker.getAccount();
    result.setEquity(account.equity());
    result.setCash(account.cash());
    // last_equity (prior market close) backs the dashboard's live intraday "today" figure (equity -
    // last_equity). Informational, not a gate input, and null-safe: a null lastEquity leaves the
    // optional field absent so the dashboard's "today" falls back to the last completed daily bar.
    result.setLastEquity(account.lastEquity());
    // Informational account identity for the tenant dashboard (not used by any gate). Null-safe: a
    // null accountNumber simply leaves the optional field absent.
    result.setAccountNumber(account.accountNumber());
    return result;
  }
}
