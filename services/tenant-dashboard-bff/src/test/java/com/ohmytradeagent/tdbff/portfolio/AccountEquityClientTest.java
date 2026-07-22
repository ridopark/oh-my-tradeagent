package com.ohmytradeagent.tdbff.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AccountSnapshotRequest;
import com.ohmytradeagent.contract.AccountSnapshotResult;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountEquityClientTest {

  private WorkflowStub stubReturning(WorkflowClient client) {
    WorkflowStub stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(eq("AccountSnapshotWorkflow"), any(WorkflowOptions.class)))
        .thenReturn(stub);
    return stub;
  }

  @Test
  void returnsEquityAndAccountNumberFromASingleSnapshot() throws Exception {
    WorkflowClient client = mock(WorkflowClient.class);
    WorkflowStub stub = stubReturning(client);
    AccountSnapshotResult result = new AccountSnapshotResult();
    result.setSchemaVersion(1L);
    result.setEquity(new BigDecimal("10000.00"));
    result.setAccountNumber("PA3ER05HLHMB");
    result.setLastEquity(new BigDecimal("10250.00"));
    when(stub.getResult(anyLong(), any(TimeUnit.class), eq(AccountSnapshotResult.class)))
        .thenReturn(result);

    var acct =
        new AccountEquityClient(client, "orchestrator-core").snapshotFor("acme", "alpaca-paper");

    assertThat(acct.equity()).isEqualByComparingTo(new BigDecimal("10000.00"));
    assertThat(acct.accountNumber()).isEqualTo("PA3ER05HLHMB");
    // last_equity (prior market close) carries through end-to-end so the header can compute the
    // live intraday "today" figure (equity - last_equity).
    assertThat(acct.lastEquity()).isEqualByComparingTo(new BigDecimal("10250.00"));
    verify(stub, never()).cancel();
  }

  @Test
  void forwardsTenantIdOnTheRequestSoExecReadsTheTenantsOwnAccount() throws Exception {
    WorkflowClient client = mock(WorkflowClient.class);
    WorkflowStub stub = stubReturning(client);
    when(stub.getResult(anyLong(), any(TimeUnit.class), eq(AccountSnapshotResult.class)))
        .thenReturn(new AccountSnapshotResult());

    new AccountEquityClient(client, "orchestrator-core").snapshotFor("acme", "alpaca-paper");

    ArgumentCaptor<Object> req = ArgumentCaptor.forClass(Object.class);
    verify(stub).start(req.capture());
    AccountSnapshotRequest sent = (AccountSnapshotRequest) req.getValue();
    assertThat(sent.getTenantId()).isEqualTo("acme");
    assertThat(sent.getBrokerTarget())
        .isEqualTo(AccountSnapshotRequest.BrokerTarget.fromValue("alpaca-paper"));
  }

  @Test
  void blankTenantLeavesTenantIdUnsetSoExecFallsBackToAccountLevel() throws Exception {
    WorkflowClient client = mock(WorkflowClient.class);
    WorkflowStub stub = stubReturning(client);
    when(stub.getResult(anyLong(), any(TimeUnit.class), eq(AccountSnapshotResult.class)))
        .thenReturn(new AccountSnapshotResult());

    new AccountEquityClient(client, "orchestrator-core").snapshotFor("  ", "alpaca-paper");

    ArgumentCaptor<Object> req = ArgumentCaptor.forClass(Object.class);
    verify(stub).start(req.capture());
    assertThat(((AccountSnapshotRequest) req.getValue()).getTenantId()).isNull();
  }

  @Test
  void timeoutCancelsTheOrphanAndDegradesToNull() throws Exception {
    WorkflowClient client = mock(WorkflowClient.class);
    WorkflowStub stub = stubReturning(client);
    when(stub.getResult(anyLong(), any(TimeUnit.class), eq(AccountSnapshotResult.class)))
        .thenThrow(new TimeoutException("waited past the bound"));

    var acct =
        new AccountEquityClient(client, "orchestrator-core").snapshotFor("acme", "alpaca-paper");

    assertThat(acct.equity()).isNull();
    assertThat(acct.accountNumber()).isNull();
    verify(stub).cancel(); // the still-running workflow must not be left as an orphan
  }

  @Test
  void runtimeFailureAfterStartCancelsOrphanAndDegradesToNull() throws Exception {
    WorkflowClient client = mock(WorkflowClient.class);
    WorkflowStub stub = stubReturning(client);
    when(stub.getResult(anyLong(), any(TimeUnit.class), eq(AccountSnapshotResult.class)))
        .thenThrow(new IllegalStateException("temporal unreachable"));

    var acct =
        new AccountEquityClient(client, "orchestrator-core").snapshotFor("acme", "alpaca-paper");

    assertThat(acct.equity()).isNull();
    assertThat(acct.accountNumber()).isNull();
    // start() succeeded before getResult() threw, so the workflow is running — it must be
    // cancelled,
    // not left to orphan until its scheduleToClose timeout.
    verify(stub).cancel();
  }
}
