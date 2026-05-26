package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.RiskBreachPayload;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface CopytradeSignalWorkflow {

  @WorkflowMethod
  String process(CopytradeSignalPayload payload);

  /**
   * Phase 2b placeholder fill receiver. No production signal source yet; Phase 3 wires the fill
   * listener (Activity or broker WS subscription) that signals this method when the broker reports
   * the entry filled. Keeping the handler in the workflow interface from Phase 2b avoids a
   * non-deterministic schema change once the source comes online.
   */
  @SignalMethod
  void onFill(FillSignalPayload event);

  /**
   * Phase 5: kill-switch cascade. Sets an internal flag that short-circuits the BTO await + STC
   * dispatch paths. Cannot unilaterally abort an in-flight Activity; reconciliation closes any
   * orphan broker order.
   */
  @SignalMethod
  void riskBreach(RiskBreachPayload payload);
}
