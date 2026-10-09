package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThatCode;

import io.temporal.testing.WorkflowReplayer;
import org.junit.jupiter.api.Test;

/**
 * #930: v3 anchors the adoption on the entry BUY instead of the latest fill. The fixture was
 * recorded from the pre-#930 (v2) impl adopting with the latest fill = the last SELL, so its child
 * PositionWorkflow id is keyed to the STC's signal. It must replay unchanged (v2 keeps the old
 * anchor); ungated, the new anchor changes the recorded commands.
 */
class AdoptionWorkflowImplLastSellAnchorReplayTest {

  @Test
  void pre930LastSellAnchorHistory_replaysWithoutNonDeterminism() {
    assertThatCode(
            () ->
                WorkflowReplayer.replayWorkflowExecutionFromResource(
                    "temporal/replay/adoption-pre-930-last-sell-anchor-history.json",
                    AdoptionWorkflowImpl.class))
        .doesNotThrowAnyException();
  }
}
