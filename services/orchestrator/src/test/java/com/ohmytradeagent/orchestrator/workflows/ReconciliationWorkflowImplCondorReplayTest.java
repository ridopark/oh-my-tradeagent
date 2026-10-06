package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThatCode;

import io.temporal.testing.WorkflowReplayer;
import org.junit.jupiter.api.Test;

/**
 * Gated-condor Phase 4 (#901): the recon missing-branch condor-owner probe is a NEW activity
 * command. A recon run in flight across the deploy roll replays its pre-change history against the
 * new code; without a version gate the extra command is a non-determinism error that wedges the
 * recon schedule (overlap=SKIP). The fixture was recorded from the pre-change impl walking the
 * missing branch (HasRunningOwnerForOcc follows SumRunningOwnerRemainingQtyForOcc, no condor
 * probe), so it fails replay unless the probe is gated.
 */
class ReconciliationWorkflowImplCondorReplayTest {

  private static final String FIXTURE =
      "temporal/replay/recon-pre-condor-owner-missing-branch-history.json";

  @Test
  void preCondorMissingBranchHistory_replaysWithoutNonDeterminism() {
    assertThatCode(
            () ->
                WorkflowReplayer.replayWorkflowExecutionFromResource(
                    FIXTURE, ReconciliationWorkflowImpl.class))
        .doesNotThrowAnyException();
  }
}
