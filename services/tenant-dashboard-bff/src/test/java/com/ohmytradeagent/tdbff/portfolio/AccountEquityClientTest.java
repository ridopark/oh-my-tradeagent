package com.ohmytradeagent.tdbff.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AccountSnapshotResult;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class AccountEquityClientTest {

  private WorkflowStub stubReturning(WorkflowClient client) {
    WorkflowStub stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(eq("AccountSnapshotWorkflow"), any(WorkflowOptions.class)))
        .thenReturn(stub);
    return stub;
  }

  @Test
  void timeoutCancelsTheOrphanAndDegradesToNull() throws Exception {
    WorkflowClient client = mock(WorkflowClient.class);
    WorkflowStub stub = stubReturning(client);
    when(stub.getResult(anyLong(), any(TimeUnit.class), eq(AccountSnapshotResult.class)))
        .thenThrow(new TimeoutException("waited past the bound"));

    var equity = new AccountEquityClient(client, "orchestrator-core").equityFor("alpaca-paper");

    assertThat(equity).isNull();
    verify(stub).cancel(); // the still-running workflow must not be left as an orphan
  }

  @Test
  void runtimeFailureDegradesToNullWithoutCancelling() throws Exception {
    WorkflowClient client = mock(WorkflowClient.class);
    WorkflowStub stub = stubReturning(client);
    when(stub.getResult(anyLong(), any(TimeUnit.class), eq(AccountSnapshotResult.class)))
        .thenThrow(new IllegalStateException("temporal unreachable"));

    var equity = new AccountEquityClient(client, "orchestrator-core").equityFor("alpaca-paper");

    assertThat(equity).isNull();
    verify(stub, never()).cancel(); // cancel is timeout-only; a start/connect failure has no orphan
  }
}
