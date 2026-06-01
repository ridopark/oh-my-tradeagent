package com.ohmytradeagent.exec.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AccountSnapshotRequest;
import com.ohmytradeagent.contract.AccountSnapshotResult;
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
    when(broker.getAccountEquity()).thenReturn(new BigDecimal("123456.78"));
    AccountSnapshotExecActivityImpl impl = new AccountSnapshotExecActivityImpl(broker);

    AccountSnapshotRequest req = new AccountSnapshotRequest();
    req.setSchemaVersion(1L);
    req.setBrokerTarget(AccountSnapshotRequest.BrokerTarget.ALPACA_PAPER);

    AccountSnapshotResult result = impl.accountSnapshot(req);

    assertThat(result.getEquity()).isEqualByComparingTo(new BigDecimal("123456.78"));
    assertThat(result.getSchemaVersion()).isEqualTo(1L);
  }

  // Issue #323: the activity must also surface the broker's getAccountCash() as the result `cash`
  // (the cash component of the notional-cap gate's cost-basis capital base, cash +
  // sum_open_notional).
  @Test
  void accountSnapshot_surfacesBrokerCash() {
    OptionsBroker broker = mock(OptionsBroker.class);
    when(broker.getAccountEquity()).thenReturn(new BigDecimal("123456.78"));
    when(broker.getAccountCash()).thenReturn(new BigDecimal("42000.00"));
    AccountSnapshotExecActivityImpl impl = new AccountSnapshotExecActivityImpl(broker);

    AccountSnapshotRequest req = new AccountSnapshotRequest();
    req.setSchemaVersion(1L);
    req.setBrokerTarget(AccountSnapshotRequest.BrokerTarget.ALPACA_PAPER);

    AccountSnapshotResult result = impl.accountSnapshot(req);

    assertThat(result.getCash()).isEqualByComparingTo(new BigDecimal("42000.00"));
  }
}
