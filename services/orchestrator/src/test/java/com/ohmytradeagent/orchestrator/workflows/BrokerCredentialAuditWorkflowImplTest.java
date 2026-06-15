package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ohmytradeagent.contract.BrokerCredentialAuditRequest;
import com.ohmytradeagent.orchestrator.activities.BrokerCredentialAuditActivities;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * BrokerCredentialAuditWorkflow is a single-step carrier: it dispatches {@code record} exactly once
 * and completes. The activity is mocked. Two invariants are pinned: (1) the workflow forwards the
 * exact request unchanged; (2) the unlimited-retry policy survives a transient activity failure
 * (one throw, then success) without any {@code Thread.sleep} — the test environment time-skips
 * across the retry backoff.
 */
class BrokerCredentialAuditWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";

  private TestWorkflowEnvironment env;
  private BrokerCredentialAuditActivities audit;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    Worker worker = env.newWorker(CORE_QUEUE);
    worker.registerWorkflowImplementationTypes(BrokerCredentialAuditWorkflowImpl.class);

    audit = Mockito.mock(BrokerCredentialAuditActivities.class);
    worker.registerActivitiesImplementations(audit);
    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  private static BrokerCredentialAuditRequest request() {
    BrokerCredentialAuditRequest req = new BrokerCredentialAuditRequest();
    req.setSchemaVersion(1L);
    req.setTenantId("acme");
    req.setProvider("alpaca");
    req.setChangeType(BrokerCredentialAuditRequest.ChangeType.CREATE);
    req.setOutcome(BrokerCredentialAuditRequest.Outcome.SAVED);
    req.setActor("api-gateway:/broker-credentials");
    req.setOccurredAt(OffsetDateTime.of(2026, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC));
    req.setBrokerAccountId("PA3FKGPFYPLH");
    req.setCredentialVersion(1L);
    req.setKekVersion(1L);
    req.setCorrelationId("corr-123");
    return req;
  }

  private BrokerCredentialAuditWorkflow newStub() {
    return env.getWorkflowClient()
        .newWorkflowStub(
            BrokerCredentialAuditWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(CORE_QUEUE)
                .setWorkflowId("t-acme/_broker/cred-audit/corr-123")
                .build());
  }

  @Test
  void invokesRecordExactlyOnceWithTheExactRequestAndCompletes() {
    doNothing().when(audit).record(Mockito.any());

    BrokerCredentialAuditRequest req = request();
    assertThatCode(() -> newStub().record(req)).doesNotThrowAnyException();

    ArgumentCaptor<BrokerCredentialAuditRequest> captor =
        ArgumentCaptor.forClass(BrokerCredentialAuditRequest.class);
    verify(audit, times(1)).record(captor.capture());
    assertThat(captor.getValue()).isEqualTo(req);
  }

  @Test
  void unlimitedRetrySurvivesATransientActivityFailure() {
    // Throw once (transient), then succeed. With maximumAttempts=0 the workflow retries and the
    // test environment time-skips across the backoff — no Thread.sleep. A bounded policy of 1
    // attempt would surface the failure; this proves the unlimited retry.
    doThrow(new RuntimeException("transient orchestrator/DB blip"))
        .doNothing()
        .when(audit)
        .record(Mockito.any());

    assertThatCode(() -> newStub().record(request())).doesNotThrowAnyException();

    verify(audit, times(2)).record(Mockito.any());
  }
}
