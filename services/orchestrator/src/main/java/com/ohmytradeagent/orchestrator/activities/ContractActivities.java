package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.orchestrator.domain.ContractResolveInput;
import com.ohmytradeagent.orchestrator.domain.ContractResolveResult;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface ContractActivities {

  ContractResolveResult resolve(ContractResolveInput input);
}
