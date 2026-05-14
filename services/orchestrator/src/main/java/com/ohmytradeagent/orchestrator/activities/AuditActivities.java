package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.AuditEvent;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface AuditActivities {

  void log(AuditEvent event);
}
