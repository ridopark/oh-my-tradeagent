package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.CopytradeDeriskPayload;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * PLAN-2026-08-04-copytrade-derisk-followup-cue (Phase 2): a NEW, isolated Temporal workflow that
 * acts on a de-risk cue — a signal author following a BTO with a separate "0-or-hero" /
 * "use-your-own-stop" escalation message. Started by the signal-source-discord sidecar per
 * subscribing tenant, exactly as {@link CopytradeSignalWorkflow} is started per signal.
 *
 * <p>On the cue it (1) trims the attributed open copytrade position to {@code derisk_keep_fraction}
 * via the pre-existing fraction-based {@code PositionWorkflow.partialExit} signal and (2) arms the
 * existing chandelier trailing stop on the remainder via the already-version-gated {@code
 * armChandelier} signal. Gated behind the per-tenant {@code derisk_on_followup_cue} config flag —
 * fully dark (no signals) until enabled.
 *
 * <p>Replay-safety: this is a brand-new workflow type with NO prior histories, so its command flow
 * needs NO {@code Workflow.getVersion} gate. It reaches the target position ONLY through the two
 * already-safe {@code PositionWorkflow} signal handlers ({@code partialExit}, pre-existing; {@code
 * armChandelier}, already gated behind {@code VERSION_CHANDELIER}), adding no new signal handler
 * and no new command shape to any running workflow.
 */
@WorkflowInterface
public interface CopytradeDeriskWorkflow {

  @WorkflowMethod
  String process(CopytradeDeriskPayload payload);
}
