package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThatCode;

import io.temporal.testing.WorkflowReplayer;
import org.junit.jupiter.api.Test;

/**
 * #930: the worthless-close refusal and the adoption-loop page add audit reads ({@code
 * CountPriorByKind}) to the expiry-day auto-adopt path. A recon run in flight across the deploy
 * replays its pre-change history; the fixture was recorded from the pre-#930 impl auto-adopting a
 * 0DTE OCC on its expiry day before the close, so it fails replay unless the reads are gated by
 * {@code VERSION_EXPIRY_PINGPONG}.
 */
class ReconciliationWorkflowImplExpiryPingpongReplayTest {

  @Test
  void pre930ExpiryDayAdoptionHistory_replaysWithoutNonDeterminism() {
    assertThatCode(
            () ->
                WorkflowReplayer.replayWorkflowExecutionFromResource(
                    "temporal/replay/recon-pre-930-expiry-day-adopt-history.json",
                    ReconciliationWorkflowImpl.class,
                    ReconciliationWorkflowImplTest.RecordingAdoptionWorkflowImpl.class))
        .doesNotThrowAnyException();
  }
}
