package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface CopytradeSignalWorkflow {

  @WorkflowMethod
  String process(CopytradeSignalPayload payload);
}
