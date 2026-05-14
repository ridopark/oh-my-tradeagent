package com.ohmytradeagent.contract.identity;

/**
 * Canonical workflow-ID + Search-Attribute shapes. The orchestrator's bootstrappers, risk gate,
 * cascade Activity, and the api-gateway controllers all build the same strings; centralising them
 * here is the single source of truth — a future rename (e.g. tenancy prefix) updates one file.
 *
 * <p>Also provides {@link #escapeForVisibilityQuery(String)} for safely interpolating
 * tenant/strategy/symbol values into Temporal Visibility query strings.
 */
public final class WorkflowIds {

  private WorkflowIds() {}

  /** Workflow ID for the {@code KillSwitchWorkflow} owning a {@code (tenant, strategy)} pair. */
  public static String killswitch(String tenantId, String strategyId) {
    return tenantStrategy(tenantId, strategyId) + "/killswitch";
  }

  /** Value of the {@code TenantStrategy} custom Search Attribute. */
  public static String tenantStrategy(String tenantId, String strategyId) {
    return "t-" + tenantId + "/s-" + strategyId;
  }

  /**
   * Workflow ID prefix for a {@code PositionWorkflow}; {@code entrySignalId} disambiguates re-BTOs.
   */
  public static String position(
      String tenantId, String strategyId, String occSymbol, String entrySignalId) {
    return tenantStrategy(tenantId, strategyId) + "/pos/" + occSymbol + "/" + entrySignalId;
  }

  /** Workflow ID prefix for {@code ReconciliationWorkflow} runs. The scheduler appends a run-id. */
  public static String reconciliationPrefix(
      String tenantId, String strategyId, String brokerTarget) {
    return tenantStrategy(tenantId, strategyId) + "/recon/" + brokerTarget + "/";
  }

  /**
   * Escape a value before interpolating it into a Temporal Visibility query. Visibility query
   * grammar uses single-quoted strings; a single-quote inside a value would break the query (or, in
   * a hostile setting, allow query injection). Doubles every quote per ANSI-SQL convention.
   */
  public static String escapeForVisibilityQuery(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("'", "''");
  }
}
