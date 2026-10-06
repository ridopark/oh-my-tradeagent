package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.activities.CondorMarketActivity.CondorLeg;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * Input to {@link CondorHoldWorkflow}: the filled condor as the session saw it. {@code credit} is
 * the achieved per-combo credit (positive); {@code legs} are short call, short put, long call, long
 * put. A plain serializable POJO internal to the orchestrator.
 */
public class CondorHoldWorkflowInput implements Serializable {

  private static final long serialVersionUID = 1L;

  public static final long SCHEMA_VERSION = 1L;

  private long schemaVersion = SCHEMA_VERSION;
  private String tenantId;
  private String strategyId;
  private String brokerTarget;
  private String attemptId;
  private String etDate;
  private String underlying;
  private long qty;
  private BigDecimal credit;
  private List<CondorLeg> legs;

  public CondorHoldWorkflowInput() {}

  public CondorHoldWorkflowInput(
      String tenantId,
      String strategyId,
      String brokerTarget,
      String attemptId,
      String etDate,
      String underlying,
      long qty,
      BigDecimal credit,
      List<CondorLeg> legs) {
    this.tenantId = tenantId;
    this.strategyId = strategyId;
    this.brokerTarget = brokerTarget;
    this.attemptId = attemptId;
    this.etDate = etDate;
    this.underlying = underlying;
    this.qty = qty;
    this.credit = credit;
    this.legs = legs;
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

  public String getBrokerTarget() {
    return brokerTarget;
  }

  public void setBrokerTarget(String brokerTarget) {
    this.brokerTarget = brokerTarget;
  }

  public String getAttemptId() {
    return attemptId;
  }

  public void setAttemptId(String attemptId) {
    this.attemptId = attemptId;
  }

  public String getEtDate() {
    return etDate;
  }

  public void setEtDate(String etDate) {
    this.etDate = etDate;
  }

  public String getUnderlying() {
    return underlying;
  }

  public void setUnderlying(String underlying) {
    this.underlying = underlying;
  }

  public long getQty() {
    return qty;
  }

  public void setQty(long qty) {
    this.qty = qty;
  }

  public BigDecimal getCredit() {
    return credit;
  }

  public void setCredit(BigDecimal credit) {
    this.credit = credit;
  }

  public List<CondorLeg> getLegs() {
    return legs;
  }

  public void setLegs(List<CondorLeg> legs) {
    this.legs = legs;
  }
}
