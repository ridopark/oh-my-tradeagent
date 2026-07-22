package com.ohmytradeagent.exec.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AccountSnapshotRequest;
import com.ohmytradeagent.contract.AccountSnapshotResult;
import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Issue #317: unit-pins the thin account-snapshot Activity wrapper. It must surface the broker's
 * {@code getAccountEquity()} as the result {@code equity} and stamp the schema version — the
 * orchestrator's notional-cap gate depends on this exact carry-over.
 */
class AccountSnapshotExecActivityImplTest {

  @Test
  void accountSnapshot_surfacesBrokerEquityAndSchemaVersion() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getAccount())
        .thenReturn(new OptionsBroker.AccountSummary(new BigDecimal("123456.78"), null, null));
    AccountSnapshotExecActivityImpl impl =
        new AccountSnapshotExecActivityImpl(
            new com.ohmytradeagent.exec.broker.FixedBrokerClientRegistry(broker));

    AccountSnapshotRequest req = new AccountSnapshotRequest();
    req.setSchemaVersion(1L);
    req.setBrokerTarget(AccountSnapshotRequest.BrokerTarget.ALPACA_PAPER);

    AccountSnapshotResult result = impl.accountSnapshot(req);

    assertThat(result.getEquity()).isEqualByComparingTo(new BigDecimal("123456.78"));
    assertThat(result.getSchemaVersion()).isEqualTo(1L);
    // A null broker accountNumber simply leaves the optional field absent.
    assertThat(result.getAccountNumber()).isNull();
    // A null broker lastEquity likewise leaves the optional field absent (never fabricated).
    assertThat(result.getLastEquity()).isNull();
  }

  // The activity must surface the broker's lastEquity (prior market close) as the result
  // last_equity so the dashboard can compute the live intraday "today" figure (equity -
  // last_equity). Informational carry-over, not a gate input.
  @Test
  void accountSnapshot_surfacesBrokerLastEquity() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getAccount())
        .thenReturn(
            new OptionsBroker.AccountSummary(
                new BigDecimal("50477.06"),
                new BigDecimal("42000.00"),
                "PA3ER05HLHMB",
                new BigDecimal("52259.56")));
    AccountSnapshotExecActivityImpl impl =
        new AccountSnapshotExecActivityImpl(
            new com.ohmytradeagent.exec.broker.FixedBrokerClientRegistry(broker));

    AccountSnapshotRequest req = new AccountSnapshotRequest();
    req.setSchemaVersion(1L);
    req.setBrokerTarget(AccountSnapshotRequest.BrokerTarget.ALPACA_PAPER);

    AccountSnapshotResult result = impl.accountSnapshot(req);

    assertThat(result.getLastEquity()).isEqualByComparingTo(new BigDecimal("52259.56"));
  }

  // Issue #323: the activity must also surface the broker's getAccountCash() as the result `cash`
  // (the cash component of the notional-cap gate's cost-basis capital base, cash +
  // sum_open_notional).
  @Test
  void accountSnapshot_surfacesBrokerCash() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getAccount())
        .thenReturn(
            new OptionsBroker.AccountSummary(
                new BigDecimal("123456.78"), new BigDecimal("42000.00"), "PA3ER05HLHMB"));
    AccountSnapshotExecActivityImpl impl =
        new AccountSnapshotExecActivityImpl(
            new com.ohmytradeagent.exec.broker.FixedBrokerClientRegistry(broker));

    AccountSnapshotRequest req = new AccountSnapshotRequest();
    req.setSchemaVersion(1L);
    req.setBrokerTarget(AccountSnapshotRequest.BrokerTarget.ALPACA_PAPER);

    AccountSnapshotResult result = impl.accountSnapshot(req);

    assertThat(result.getCash()).isEqualByComparingTo(new BigDecimal("42000.00"));
    // Informational account_number flows through from the broker summary to the result.
    assertThat(result.getAccountNumber()).isEqualTo("PA3ER05HLHMB");
  }

  // Issue #323 single-fetch: the activity must read equity AND cash from ONE broker account read
  // (getAccount) — not via separate getAccountEquity()/getAccountCash() calls, each of which would
  // issue its own /v2/account round-trip. Verify getAccount is the only broker interaction.
  @Test
  void accountSnapshot_readsAccountOnce_singleBrokerFetch() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getAccount())
        .thenReturn(
            new OptionsBroker.AccountSummary(
                new BigDecimal("123456.78"), new BigDecimal("42000.00"), null));
    AccountSnapshotExecActivityImpl impl =
        new AccountSnapshotExecActivityImpl(
            new com.ohmytradeagent.exec.broker.FixedBrokerClientRegistry(broker));

    AccountSnapshotRequest req = new AccountSnapshotRequest();
    req.setSchemaVersion(1L);
    req.setBrokerTarget(AccountSnapshotRequest.BrokerTarget.ALPACA_PAPER);

    impl.accountSnapshot(req);

    verify(broker).getAccount();
    verifyNoMoreInteractions(broker);
  }

  // P4-c-b: a present tenant_id resolves THAT tenant's broker (keyed on the tenant, not the
  // ACCOUNT_LEVEL sentinel) so the cap-basis cash reads the tenant's own account.
  @Test
  void accountSnapshot_resolvesByTenantWhenPresent() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getAccount())
        .thenReturn(new OptionsBroker.AccountSummary(BigDecimal.ONE, BigDecimal.TEN, null));
    BrokerClientRegistry registry = mock(BrokerClientRegistry.class);
    when(registry.brokerFor(eq("staging_paper"), eq("alpaca"))).thenReturn(broker);
    AccountSnapshotExecActivityImpl impl = new AccountSnapshotExecActivityImpl(registry);

    AccountSnapshotRequest req = new AccountSnapshotRequest();
    req.setSchemaVersion(1L);
    req.setBrokerTarget(AccountSnapshotRequest.BrokerTarget.ALPACA_PAPER);
    req.setTenantId("staging_paper");

    impl.accountSnapshot(req);

    verify(registry).brokerFor("staging_paper", "alpaca");
  }

  // P4-c-b: a null/blank tenant_id (the dashboard account-level caller, or a legacy request) falls
  // back to ACCOUNT_LEVEL — never rejects (would regress the live path mid-rollout).
  @Test
  void accountSnapshot_fallsBackToAccountLevelWhenTenantBlank() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getAccount())
        .thenReturn(new OptionsBroker.AccountSummary(BigDecimal.ONE, BigDecimal.TEN, null));
    BrokerClientRegistry registry = mock(BrokerClientRegistry.class);
    when(registry.brokerFor(eq(BrokerClientRegistry.ACCOUNT_LEVEL), eq("alpaca")))
        .thenReturn(broker);
    AccountSnapshotExecActivityImpl impl = new AccountSnapshotExecActivityImpl(registry);

    for (String blank : new String[] {null, "", "   "}) {
      AccountSnapshotRequest req = new AccountSnapshotRequest();
      req.setSchemaVersion(1L);
      req.setBrokerTarget(AccountSnapshotRequest.BrokerTarget.ALPACA_PAPER);
      req.setTenantId(blank);
      impl.accountSnapshot(req);
    }

    verify(registry, org.mockito.Mockito.times(3))
        .brokerFor(BrokerClientRegistry.ACCOUNT_LEVEL, "alpaca");
  }

  // P4-c-b behavior-preserving proof: under a tenant-ignoring source (env-fallback returns ONE
  // account for every key), the cash is identical whether tenant_id is set or absent — the live
  // single-tenant cap decision is unchanged.
  @Test
  void accountSnapshot_behaviorPreservingUnderTenantIgnoringSource() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getAccount())
        .thenReturn(
            new OptionsBroker.AccountSummary(
                new BigDecimal("123456.78"), new BigDecimal("42000.00"), "PA3ER05HLHMB"));
    // FixedBrokerClientRegistry returns the same broker for EVERY key — exactly the env-fallback
    // property (tenant-invariant single account).
    AccountSnapshotExecActivityImpl impl =
        new AccountSnapshotExecActivityImpl(
            new com.ohmytradeagent.exec.broker.FixedBrokerClientRegistry(broker));

    AccountSnapshotRequest withTenant = new AccountSnapshotRequest();
    withTenant.setSchemaVersion(1L);
    withTenant.setBrokerTarget(AccountSnapshotRequest.BrokerTarget.ALPACA_PAPER);
    withTenant.setTenantId("staging_paper");
    AccountSnapshotRequest without = new AccountSnapshotRequest();
    without.setSchemaVersion(1L);
    without.setBrokerTarget(AccountSnapshotRequest.BrokerTarget.ALPACA_PAPER);

    AccountSnapshotResult a = impl.accountSnapshot(withTenant);
    AccountSnapshotResult b = impl.accountSnapshot(without);

    assertThat(a.getCash()).isEqualByComparingTo(b.getCash());
    assertThat(a.getEquity()).isEqualByComparingTo(b.getEquity());
    assertThat(a.getAccountNumber()).isEqualTo(b.getAccountNumber());
  }
}
