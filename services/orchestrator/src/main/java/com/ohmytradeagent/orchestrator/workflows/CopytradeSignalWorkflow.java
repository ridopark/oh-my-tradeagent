package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.CopytradeEntryStatus;
import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.RiskBreachPayload;
import io.temporal.workflow.QueryMethod;
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

  /**
   * PLAN-2026-08-10-live-manual-bto: what happened to this signal's ENTRY. Added so the /live
   * manual-BTO panel can tell an accepted entry from one a gate refused — without it, a rejection
   * (EOD cutoff, notional cap, kill switch, missing live promotion) is indistinguishable from
   * success until someone reads {@code audit_log}.
   *
   * <p>A Query emits NO Temporal commands, so this handler is replay-inert: it reports state the
   * workflow already tracks and can be added to in-flight executions safely. It is also answerable
   * on a CLOSED execution, which matters because this workflow completes ~90s after submit (on fill
   * or entry-TTL expiry) while the dashboard is still polling.
   *
   * <p>Meaningful for {@code action=BTO} only. An STC/AVG signal opens no entry, so its status
   * stays {@code PENDING} for the life of the execution — the manual-entry caller only ever queries
   * workflows it started itself, all of which are BTOs.
   */
  @QueryMethod
  CopytradeEntryStatus entryStatus();
}
