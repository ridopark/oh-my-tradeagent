package com.ohmytradeagent.exec.broker;

/**
 * Three-state outcome of a broker cancel attempt.
 *
 * <ul>
 *   <li>{@link Outcome#CANCELLED} — broker confirmed the cancel; the journal flips to CANCELLED.
 *   <li>{@link Outcome#FAILED} — broker rejected the cancel for a non-fill reason (validation,
 *       unknown order id, transient 4xx). The journal records {@code last_error} but state stays
 *       SUBMITTED; reconciliation surfaces it later.
 *   <li>{@link Outcome#ALREADY_FILLED} — issue #165: broker rejected the cancel because the order
 *       had already filled (cancel-on-filled race). The activity reconciles the journal to FILLED
 *       via {@link OptionsBroker#getFillDetail(String)} so the orchestrator can spawn the missing
 *       PositionWorkflow instead of orphaning the position.
 * </ul>
 *
 * <p>The {@link #cancelled()} accessor is retained for callers that only care whether the cancel
 * succeeded; new callers should switch on {@link #outcome()}.
 */
public record CancelResponse(Outcome outcome, String brokerReason) {

  public enum Outcome {
    CANCELLED,
    FAILED,
    ALREADY_FILLED
  }

  public static CancelResponse ok() {
    return new CancelResponse(Outcome.CANCELLED, null);
  }

  public static CancelResponse failed(String brokerReason) {
    return new CancelResponse(Outcome.FAILED, brokerReason);
  }

  public static CancelResponse alreadyFilled(String brokerReason) {
    return new CancelResponse(Outcome.ALREADY_FILLED, brokerReason);
  }

  public boolean cancelled() {
    return outcome == Outcome.CANCELLED;
  }
}
