package com.ohmytradeagent.orchestrator.platform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Phase 6: tenant-level configuration loaded from {@code tenants/<tenant>/tenant.yaml}. Today the
 * only field that drives behavior is {@link #accountDailyLossThreshold} (the account-level loss
 * cap); the rest of the file ({@code tenant_id}, {@code display_name}, {@code strategies}) is
 * informational and is tolerated via {@link JsonIgnoreProperties} so this minimal POJO does not
 * have to enumerate every key.
 *
 * <p>Opt-in / inert: a null {@code account_daily_loss_threshold} disables the cap entirely
 * (AccountKillSwitchWorkflow never trips), so existing tenants are unaffected.
 *
 * <p>Deferred (out of scope this phase): there is no DB-backed tenant-config store or seed
 * reconciler — the per-strategy DB path ({@code DbStrategyRegistry}) is NOT mirrored here. This is
 * a YAML-only read, matching {@code YamlStrategyRegistry}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TenantConfig {

  @JsonProperty("account_daily_loss_threshold")
  private BigDecimal accountDailyLossThreshold;

  @JsonProperty("account_daily_loss_pct")
  private BigDecimal accountDailyLossPct;

  public BigDecimal getAccountDailyLossThreshold() {
    return accountDailyLossThreshold;
  }

  public void setAccountDailyLossThreshold(BigDecimal accountDailyLossThreshold) {
    this.accountDailyLossThreshold = accountDailyLossThreshold;
  }

  /**
   * The account daily-loss cap expressed as a FRACTION of start-of-day account equity (e.g. {@code
   * 0.40} for 40%). When set ({@code > 0}) and start-of-day equity is known, the {@code
   * AccountKillSwitchWorkflow} uses {@code pct x sodEquity} as the effective threshold in
   * preference to the absolute {@link #accountDailyLossThreshold}. A null/≤0 value leaves the pct
   * cap disabled (the absolute threshold, if any, still applies).
   */
  public BigDecimal getAccountDailyLossPct() {
    return accountDailyLossPct;
  }

  public void setAccountDailyLossPct(BigDecimal accountDailyLossPct) {
    this.accountDailyLossPct = accountDailyLossPct;
  }
}
