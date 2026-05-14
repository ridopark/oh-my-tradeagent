package com.ohmytradeagent.audit;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Phase 0 placeholder: accepts a {@link CopytradeSignalPayload} and audit-logs it via slf4j.
 * Returns the payload's signal_id so the workflow result is verifiable in tests.
 *
 * <p>Subsequent phases evolve this workflow significantly (Phase 2a wires risk gates, Phase 2b
 * wires exec). For now this exists only to validate the Temporal cluster, custom Search Attributes
 * (TenantStrategy, ContractSymbol — only TenantStrategy applies on signal workflows), and the
 * contract DTO codegen end-to-end.
 */
@WorkflowInterface
public interface CopytradeSignalWorkflow {

  /**
   * Phase 0 body: log + return signal_id. Phase 2a replaces this with the real entry pipeline.
   *
   * @param payload parsed Discord BTO/STC/AVG line
   * @return the signal_id (for round-trip verification)
   */
  @WorkflowMethod
  String process(CopytradeSignalPayload payload);
}
