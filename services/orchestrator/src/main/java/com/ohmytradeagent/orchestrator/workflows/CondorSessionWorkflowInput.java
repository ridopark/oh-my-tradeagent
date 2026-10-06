package com.ohmytradeagent.orchestrator.workflows;

import java.io.Serializable;

/**
 * Input to {@link CondorSessionWorkflow}: only the identity — the session loads the strategy's
 * CURRENT config at run time, so a schedule created days ago never runs on a stale config. A plain
 * serializable POJO internal to the orchestrator; {@code schemaVersion} guards a newer-than-build
 * payload.
 */
public class CondorSessionWorkflowInput implements Serializable {

  private static final long serialVersionUID = 1L;

  public static final long SCHEMA_VERSION = 1L;

  private long schemaVersion = SCHEMA_VERSION;
  private String tenantId;
  private String strategyId;

  public CondorSessionWorkflowInput() {}

  public CondorSessionWorkflowInput(String tenantId, String strategyId) {
    this.tenantId = tenantId;
    this.strategyId = strategyId;
  }

  public long getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(long schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public String getStrategyId() {
    return strategyId;
  }

  public void setStrategyId(String strategyId) {
    this.strategyId = strategyId;
  }
}
