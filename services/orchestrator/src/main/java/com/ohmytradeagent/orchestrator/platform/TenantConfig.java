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
 * <p>Source-selected (account-loss-cap-db epic): read by {@code YamlTenantRegistry} (default) or
 * {@code DbTenantRegistry} per the {@code tenant.config.source} property, mirroring the
 * per-strategy {@code YamlStrategyRegistry}/{@code DbStrategyRegistry} split. A boot seed
 * reconciler warms the DB from the YAML tree before the read-source cutover.
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
   * 0.40} for 40%). When set and start-of-day equity is known, the {@code
   * AccountKillSwitchWorkflow} uses {@code pct x sodEquity} as the effective threshold in
   * preference to the absolute {@link #accountDailyLossThreshold}. A null value leaves the pct cap
   * disabled (the absolute threshold, if any, still applies).
   */
  public BigDecimal getAccountDailyLossPct() {
    return accountDailyLossPct;
  }

  /**
   * Rejects an out-of-range {@code account_daily_loss_pct} LOUDLY at config parse rather than
   * silently disabling the kill switch on real money. The value is a fraction in {@code (0, 1]}; an
   * operator typo like {@code 40} (meant {@code 0.40}) would otherwise compute an effective
   * threshold of {@code 40 x equity} — never reachable — and silently neuter the account cap with
   * no error. We REJECT (not clamp: clamping {@code 40 -> 1.0} would also effectively disable the
   * cap and hide the typo). Jackson invokes this setter during {@code YamlTenantRegistry} parse, so
   * a bad value throws at config LOAD; the boot-time {@code TenantConfigBootstrapper} reads every
   * tenant's config at startup so the misconfiguration surfaces before any workflow trades.
   */
  public void setAccountDailyLossPct(BigDecimal accountDailyLossPct) {
    if (accountDailyLossPct != null
        && (accountDailyLossPct.signum() <= 0
            || accountDailyLossPct.compareTo(BigDecimal.ONE) > 0)) {
      throw new IllegalArgumentException(
          "account_daily_loss_pct must be a fraction in (0,1], got " + accountDailyLossPct);
    }
    this.accountDailyLossPct = accountDailyLossPct;
  }
}
