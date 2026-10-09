package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThatCode;

import io.temporal.testing.WorkflowReplayer;
import org.junit.jupiter.api.Test;

/**
 * #938: the fresh broker_account_id read adds a {@code Get} (StrategyActivities) command to the
 * missing-journal branch. The fixture was recorded from the pre-#938 impl walking that branch with
 * a null schedule account (so it recorded no account probe and no config read); it fails replay
 * unless the read is gated by {@code VERSION_FRESH_ACCOUNT_ID}.
 */
class ReconciliationWorkflowImplFreshAccountReplayTest {

  @Test
  void pre938NullAccountMissingBranchHistory_replaysWithoutNonDeterminism() {
    assertThatCode(
            () ->
                WorkflowReplayer.replayWorkflowExecutionFromResource(
                    "temporal/replay/recon-pre-938-null-account-missing-branch-history.json",
                    ReconciliationWorkflowImpl.class))
        .doesNotThrowAnyException();
  }
}
