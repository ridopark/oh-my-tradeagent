package com.ohmytradeagent.audit;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

public class CopytradeSignalWorkflowImpl implements CopytradeSignalWorkflow {

  private static final Logger log = Workflow.getLogger(CopytradeSignalWorkflowImpl.class);

  @Override
  public String process(CopytradeSignalPayload payload) {
    log.info(
        "SignalReceived: tenant={} strategy={} signal_id={} action={} ticker={} expiry={} strike={} right={} price={} author={}",
        payload.getTenantId(),
        payload.getStrategyId(),
        payload.getSignalId(),
        payload.getAction(),
        payload.getTicker(),
        payload.getExpiry(),
        payload.getStrike(),
        payload.getRight(),
        payload.getPrice(),
        payload.getAuthor());
    return payload.getSignalId();
  }
}
