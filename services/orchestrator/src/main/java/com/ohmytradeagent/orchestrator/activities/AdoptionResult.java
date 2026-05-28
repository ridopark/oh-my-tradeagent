package com.ohmytradeagent.orchestrator.activities;

/**
 * Issue #239: outcome of {@link PositionAdoptionActivities#adoptOrphanPosition}. Carries the
 * terminal outcome plus, on a successful adoption, the reconstructed workflow id and broker-truth
 * provenance so the caller (and the audit trail) can confirm what was started.
 */
public final class AdoptionResult {

  /** Terminal classification of an adoption attempt. */
  public enum Outcome {
    /** A {@code PositionWorkflow} owner was reconstructed and started for the orphan. */
    ADOPTED,
    /** A live {@code PositionWorkflow} already owns the OCC — no-op (idempotency guard). */
    ALREADY_OWNED,
    /** The broker does not actually hold the lot — refused (phantom guard). */
    REFUSED_NOT_HELD,
    /**
     * No {@code entry_signal_id} could be anchored from the journal, so the canonical workflow id
     * cannot be built — refused (documented known limitation; the deferred recon auto-trigger's
     * richer evidence handles this case).
     */
    REFUSED_NO_ANCHOR
  }

  private final Outcome outcome;
  private final String workflowId;
  private final String entrySignalId;
  private final Long qty;

  private AdoptionResult(Outcome outcome, String workflowId, String entrySignalId, Long qty) {
    this.outcome = outcome;
    this.workflowId = workflowId;
    this.entrySignalId = entrySignalId;
    this.qty = qty;
  }

  public static AdoptionResult adopted(String workflowId, String entrySignalId, long qty) {
    return new AdoptionResult(Outcome.ADOPTED, workflowId, entrySignalId, qty);
  }

  public static AdoptionResult alreadyOwned() {
    return new AdoptionResult(Outcome.ALREADY_OWNED, null, null, null);
  }

  public static AdoptionResult refusedNotHeld() {
    return new AdoptionResult(Outcome.REFUSED_NOT_HELD, null, null, null);
  }

  public static AdoptionResult refusedNoAnchor() {
    return new AdoptionResult(Outcome.REFUSED_NO_ANCHOR, null, null, null);
  }

  public Outcome getOutcome() {
    return outcome;
  }

  public String getWorkflowId() {
    return workflowId;
  }

  public String getEntrySignalId() {
    return entrySignalId;
  }

  public Long getQty() {
    return qty;
  }

  @Override
  public String toString() {
    return "AdoptionResult{outcome="
        + outcome
        + ", workflowId="
        + workflowId
        + ", entrySignalId="
        + entrySignalId
        + ", qty="
        + qty
        + '}';
  }
}
