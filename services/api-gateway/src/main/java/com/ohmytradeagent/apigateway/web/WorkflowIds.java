package com.ohmytradeagent.apigateway.web;

/** Workflow-ID conventions. Matches the orchestrator's bootstrappers. */
final class WorkflowIds {

  private WorkflowIds() {}

  static String killswitch(String tenantId, String strategyId) {
    return "t-" + tenantId + "/s-" + strategyId + "/killswitch";
  }

  static String tenantStrategy(String tenantId, String strategyId) {
    return "t-" + tenantId + "/s-" + strategyId;
  }
}
