package com.ohmytradeagent.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * account-loss-cap-db epic (Phase 3): terminal outcome of {@code TenantConfigUpdateWorkflow}. The
 * activity CATCHES each {@code TenantConfigWriter} exception and coarsens it into the {@link
 * Outcome} enum (writer exception messages never surface to the client). api-gateway maps the
 * outcome to an HTTP status (UPDATED 200, REJECTED_STALE_VERSION 409, REJECTED_TIGHTEN_ONLY 403,
 * REJECTED_BELOW_FLOOR 422, REJECTED_INVALID 400, NOT_FOUND 404). Only a genuinely
 * transient/unknown fault (an {@code IllegalStateException} from a corrupt stored row) is NOT
 * coarsened — it propagates as a retryable activity failure, surfacing to the caller as a 503
 * (write disposition unknown, NEVER reported as success).
 *
 * <p>Hand-written (NOT generated) alongside the strategy-config DTOs — see {@link
 * TenantConfigUpdateRequest}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TenantConfigUpdateResult {

  @JsonProperty("schema_version")
  private Long schemaVersion;

  @JsonProperty("outcome")
  private Outcome outcome;

  @JsonProperty("new_version")
  private Long newVersion;

  public Long getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(Long schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public Outcome getOutcome() {
    return outcome;
  }

  public void setOutcome(Outcome outcome) {
    this.outcome = outcome;
  }

  public Long getNewVersion() {
    return newVersion;
  }

  public void setNewVersion(Long newVersion) {
    this.newVersion = newVersion;
  }

  /**
   * Controlled disposition of the write.
   *
   * <ul>
   *   <li>{@code UPDATED}: the CAS committed ({@code new_version} set).
   *   <li>{@code REJECTED_STALE_VERSION}: {@code expected_version} was stale (OptimisticLock).
   *   <li>{@code REJECTED_TIGHTEN_ONLY}: the write would RAISE, REMOVE, or ADD-where-none an
   *       account cap — NEVER coarsened into UPDATED.
   *   <li>{@code REJECTED_BELOW_FLOOR}: a valid tighten, but below the policy floor (near-zero caps
   *       brick the tenant's own real-money account and are irreversible tenant-side).
   *   <li>{@code REJECTED_INVALID}: a malformed value (pct outside {@code (0,1]}, or a non-positive
   *       threshold — a 0 cap is forbidden).
   *   <li>{@code NOT_FOUND}: no {@code tenant_config} row for the tenant.
   * </ul>
   */
  public enum Outcome {
    UPDATED("UPDATED"),
    REJECTED_STALE_VERSION("REJECTED_STALE_VERSION"),
    REJECTED_TIGHTEN_ONLY("REJECTED_TIGHTEN_ONLY"),
    REJECTED_BELOW_FLOOR("REJECTED_BELOW_FLOOR"),
    REJECTED_INVALID("REJECTED_INVALID"),
    NOT_FOUND("NOT_FOUND");

    private final String value;

    Outcome(String value) {
      this.value = value;
    }

    @JsonValue
    public String value() {
      return value;
    }

    @JsonCreator
    public static Outcome fromValue(String value) {
      for (Outcome o : values()) {
        if (o.value.equals(value)) {
          return o;
        }
      }
      throw new IllegalArgumentException("unknown TenantConfigUpdateResult.Outcome: " + value);
    }
  }
}
