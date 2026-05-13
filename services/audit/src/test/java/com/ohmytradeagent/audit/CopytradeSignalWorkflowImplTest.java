package com.ohmytradeagent.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CopytradeSignalWorkflowImplTest {

  private static final String TASK_QUEUE = "orchestrator-core";

  private TestWorkflowEnvironment env;
  private Worker worker;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    worker = env.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(CopytradeSignalWorkflowImpl.class);
    env.start();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void process_returnsSignalId_andCompletes() {
    CopytradeSignalWorkflow workflow =
        env.getWorkflowClient()
            .newWorkflowStub(
                CopytradeSignalWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());

    CopytradeSignalPayload payload = fixturePayload();

    String result = workflow.process(payload);

    assertThat(result).isEqualTo(payload.getSignalId());
  }

  private CopytradeSignalPayload fixturePayload() {
    CopytradeSignalPayload p = new CopytradeSignalPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId("copytrade-v1");
    p.setSignalId("1234567890123456789:0");
    p.setMessageId("1234567890123456789");
    p.setAuthor("ridopark");
    p.setPostedAt(OffsetDateTime.of(2026, 5, 16, 13, 35, 0, 0, ZoneOffset.UTC));
    p.setAction(CopytradeSignalPayload.Action.BTO);
    p.setTicker("NVDA");
    p.setExpiry(LocalDate.of(2026, 5, 16));
    p.setStrike(new java.math.BigDecimal("140.0"));
    p.setRight(CopytradeSignalPayload.Right.C);
    p.setPrice(new java.math.BigDecimal("2.30"));
    p.setTail("small");
    p.setRawLine("BTO NVDA 5/16 140C @ 2.30 small");
    return p;
  }
}
