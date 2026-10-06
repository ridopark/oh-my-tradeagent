package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThatCode;

import io.temporal.testing.WorkflowReplayer;
import org.junit.jupiter.api.Test;

/**
 * Config-driven condor sizing adds a capital-base read (a new activity command) before {@code
 * EnterCondor}. A CondorSessionWorkflow in flight across the deploy (sessions run daily
 * ~13:50-14:05 ET) must replay its pre-change history: the fixture was recorded from the
 * fixed-1-contract impl walking to EnterCondor, so it fails replay unless the read is gated by
 * {@code VERSION_CONDOR_SIZING}.
 */
class CondorSessionWorkflowImplSizingReplayTest {

  @Test
  void preConfigSizingHistory_replaysWithoutNonDeterminism() {
    assertThatCode(
            () ->
                WorkflowReplayer.replayWorkflowExecutionFromResource(
                    "temporal/replay/condor-session-pre-config-sizing-history.json",
                    CondorSessionWorkflowImpl.class))
        .doesNotThrowAnyException();
  }
}
