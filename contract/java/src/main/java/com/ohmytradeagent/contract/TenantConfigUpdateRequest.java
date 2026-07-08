package com.ohmytradeagent.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * account-loss-cap-db epic (Phase 3, tenant tighten-only write): input for the dark-gated,
 * tighten-only account-cap write. The dashboard POSTs to api-gateway {@code POST /tenant-config},
 * which starts a short-lived {@code TenantConfigUpdateWorkflow} on orchestrator-core, synchronously
 * reads its {@link TenantConfigUpdateResult}, and maps the outcome to an HTTP status. The activity
 * drives {@code TenantConfigWriter.update(tenant, newThreshold, newPct, expectedVersion, actor)} —
 * a compare-and-set gated on the SERVER-AUTHORITATIVE tighten-only + floor rules.
 *
 * <p>Hand-written (NOT generated from a JSON schema) alongside the strategy-config DTOs: the two
 * cap columns are a fixed, tiny shape with no Python consumer, so there is no {@code
 * strategy-config.json} change and no pydantic round-trip. Carries NO secret/key material.
 *
 * <p>The request represents the FULL desired {@code (threshold, pct)} state (like the strategy path
 * posts the full config). A {@code null} cap means "should be unset" — the writer's tighten-only
 * rule REJECTS removing an existing cap and REJECTS adding one where none existed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TenantConfigUpdateRequest {

  @JsonProperty("schema_version")
  private Long schemaVersion;

  @JsonProperty("tenant_id")
  private String tenantId;

  @JsonProperty("account_daily_loss_threshold")
  private BigDecimal accountDailyLossThreshold;

  @JsonProperty("account_daily_loss_pct")
  private BigDecimal accountDailyLossPct;

  @JsonProperty("expected_version")
  private long expectedVersion;

  @JsonProperty("actor")
  private String actor;

  @JsonProperty("correlation_id")
  private String correlationId;

  public Long getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(Long schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public BigDecimal getAccountDailyLossThreshold() {
    return accountDailyLossThreshold;
  }

  public void setAccountDailyLossThreshold(BigDecimal accountDailyLossThreshold) {
    this.accountDailyLossThreshold = accountDailyLossThreshold;
  }

  public BigDecimal getAccountDailyLossPct() {
    return accountDailyLossPct;
  }

  public void setAccountDailyLossPct(BigDecimal accountDailyLossPct) {
    this.accountDailyLossPct = accountDailyLossPct;
  }

  public long getExpectedVersion() {
    return expectedVersion;
  }

  public void setExpectedVersion(long expectedVersion) {
    this.expectedVersion = expectedVersion;
  }

  public String getActor() {
    return actor;
  }

  public void setActor(String actor) {
    this.actor = actor;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public void setCorrelationId(String correlationId) {
    this.correlationId = correlationId;
  }
}
