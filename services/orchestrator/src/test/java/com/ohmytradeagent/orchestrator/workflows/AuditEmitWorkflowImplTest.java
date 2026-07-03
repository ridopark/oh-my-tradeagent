package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Phase 4 coverage for the generic {@link AuditEmitWorkflow} — the audit-emit surface the
 * api-gateway uses to write a hash-chained {@code audit_log} row (it has no direct writer). The
 * workflow must build an {@code AuditEvent} from the request, fill a deterministic {@code event_id}
 * + {@code occurred_at}, and delegate the single write to {@code AuditActivities.log}.
 */
class AuditEmitWorkflowImplTest {

  private static final String CORE_QUEUE = "orchestrator-core";

  private TestWorkflowEnvironment env;

  @AfterEach
  void tearDown() {
    if (env != null) {
      env.close();
    }
  }

  private AuditEmitWorkflow startWith(AuditActivities audit) {
    env = TestWorkflowEnvironment.newInstance();
    Worker worker = env.newWorker(CORE_QUEUE);
    worker.registerWorkflowImplementationTypes(AuditEmitWorkflowImpl.class);
    worker.registerActivitiesImplementations(audit);
    env.start();
    return env.getWorkflowClient()
        .newWorkflowStub(
            AuditEmitWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());
  }

  @Test
  void emit_buildsEventFromRequest_andLogsIt() {
    AuditActivities audit = mock(AuditActivities.class);

    AuditEmitRequest request = new AuditEmitRequest();
    request.setKind("TenantDeleteRequested");
    request.setTenantId("staging-paper-2");
    request.setStrategyId("copytrade-v1");
    request.setActor("operator:ridopark");
    request.setCorrelationId("req-123");
    request.setSubject(Map.of("confirm_tenant_id", "staging-paper-2", "flag_state", "on"));

    startWith(audit).emit(request);

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, times(1)).log(captor.capture());
    AuditEvent e = captor.getValue();
    assertThat(e.getKind()).isEqualTo("TenantDeleteRequested");
    assertThat(e.getTenantId()).isEqualTo("staging-paper-2");
    assertThat(e.getStrategyId()).isEqualTo("copytrade-v1");
    assertThat(e.getActor()).isEqualTo("operator:ridopark");
    assertThat(e.getCorrelationId()).isEqualTo("req-123");
    assertThat(e.getSchemaVersion()).isEqualTo(1L);
    assertThat(e.getEventId()).isNotBlank();
    assertThat(e.getOccurredAt()).isNotNull();
    assertThat(e.getSubject()).containsEntry("confirm_tenant_id", "staging-paper-2");
  }
}
