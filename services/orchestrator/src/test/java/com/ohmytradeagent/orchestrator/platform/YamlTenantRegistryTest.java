package com.ohmytradeagent.orchestrator.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlTenantRegistryTest {

  @Test
  void loadsAccountThreshold_andToleratesLayoutKeys(@TempDir Path tenantsDir) throws Exception {
    Path file = tenantsDir.resolve("dev/tenant.yaml");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        tenant_id: dev
        display_name: Local development tenant
        strategies:
          - copytrade-v1
          - watchlist-trigger-v1
        account_daily_loss_threshold: 5000.00
        """);

    TenantConfig cfg = new YamlTenantRegistry(tenantsDir.toString()).get("dev");

    assertThat(cfg.getAccountDailyLossThreshold()).isEqualByComparingTo(new BigDecimal("5000.00"));
  }

  @Test
  void absentThreshold_isNull_capDisabled(@TempDir Path tenantsDir) throws Exception {
    // A tenant.yaml that does NOT set account_daily_loss_threshold => null => cap inert.
    Path file = tenantsDir.resolve("dev/tenant.yaml");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        tenant_id: dev
        display_name: Local development tenant
        strategies:
          - copytrade-v1
        """);

    TenantConfig cfg = new YamlTenantRegistry(tenantsDir.toString()).get("dev");

    assertThat(cfg.getAccountDailyLossThreshold()).isNull();
  }

  @Test
  void loadsAccountDailyLossPct(@TempDir Path tenantsDir) throws Exception {
    Path file = tenantsDir.resolve("dev/tenant.yaml");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        tenant_id: dev
        display_name: Local development tenant
        strategies:
          - copytrade-v1
        account_daily_loss_pct: 0.40
        """);

    TenantConfig cfg = new YamlTenantRegistry(tenantsDir.toString()).get("dev");

    assertThat(cfg.getAccountDailyLossPct()).isEqualByComparingTo(new BigDecimal("0.40"));
    // pct-only config => absolute threshold absent (null).
    assertThat(cfg.getAccountDailyLossThreshold()).isNull();
  }

  @Test
  void absentPct_isNull(@TempDir Path tenantsDir) throws Exception {
    Path file = tenantsDir.resolve("dev/tenant.yaml");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        tenant_id: dev
        account_daily_loss_threshold: 1500
        """);

    TenantConfig cfg = new YamlTenantRegistry(tenantsDir.toString()).get("dev");

    assertThat(cfg.getAccountDailyLossPct()).isNull();
    assertThat(cfg.getAccountDailyLossThreshold()).isEqualByComparingTo(new BigDecimal("1500"));
  }

  // The setter is the bound-check seam Jackson invokes during parse. Pin it directly.
  @Test
  void setAccountDailyLossPct_boundCheck() {
    TenantConfig cfg = new TenantConfig();
    // null clears (cap disabled) — allowed.
    cfg.setAccountDailyLossPct(null);
    assertThat(cfg.getAccountDailyLossPct()).isNull();
    // in-range accepted.
    cfg.setAccountDailyLossPct(new BigDecimal("0.40"));
    assertThat(cfg.getAccountDailyLossPct()).isEqualByComparingTo(new BigDecimal("0.40"));
    cfg.setAccountDailyLossPct(BigDecimal.ONE);
    assertThat(cfg.getAccountDailyLossPct()).isEqualByComparingTo(BigDecimal.ONE);
    // out-of-range rejected with field + range in the message.
    assertThatThrownBy(() -> cfg.setAccountDailyLossPct(new BigDecimal("40")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("account_daily_loss_pct")
        .hasMessageContaining("(0,1]");
    assertThatThrownBy(() -> cfg.setAccountDailyLossPct(BigDecimal.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> cfg.setAccountDailyLossPct(new BigDecimal("-0.1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // Operator typo: account_daily_loss_pct: 40 (meant 0.40). Must be REJECTED LOUDLY at config load
  // (not silently disable the kill switch) with a message naming the field + the (0,1] range.
  @Test
  void accountDailyLossPct_typoOutOfRange_rejectedLoudly(@TempDir Path tenantsDir)
      throws Exception {
    Path file = tenantsDir.resolve("dev/tenant.yaml");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        tenant_id: dev
        account_daily_loss_pct: 40
        """);

    assertThatThrownBy(() -> new YamlTenantRegistry(tenantsDir.toString()).get("dev"))
        .hasStackTraceContaining("account_daily_loss_pct")
        .hasStackTraceContaining("(0,1]");
  }

  @Test
  void accountDailyLossPct_zeroOrNegative_rejected(@TempDir Path tenantsDir) throws Exception {
    Path zero = tenantsDir.resolve("z/tenant.yaml");
    Files.createDirectories(zero.getParent());
    Files.writeString(zero, "tenant_id: z\naccount_daily_loss_pct: 0\n");
    assertThatThrownBy(() -> new YamlTenantRegistry(tenantsDir.toString()).get("z"))
        .hasStackTraceContaining("account_daily_loss_pct");

    Path neg = tenantsDir.resolve("n/tenant.yaml");
    Files.createDirectories(neg.getParent());
    Files.writeString(neg, "tenant_id: n\naccount_daily_loss_pct: -0.1\n");
    assertThatThrownBy(() -> new YamlTenantRegistry(tenantsDir.toString()).get("n"))
        .hasStackTraceContaining("account_daily_loss_pct");
  }

  @Test
  void accountDailyLossPct_validFractionAndBoundary_accepted(@TempDir Path tenantsDir)
      throws Exception {
    Path frac = tenantsDir.resolve("a/tenant.yaml");
    Files.createDirectories(frac.getParent());
    Files.writeString(frac, "tenant_id: a\naccount_daily_loss_pct: 0.40\n");
    assertThat(new YamlTenantRegistry(tenantsDir.toString()).get("a").getAccountDailyLossPct())
        .isEqualByComparingTo(new BigDecimal("0.40"));

    // Boundary 1.0 (100% of equity) is accepted — it is the inclusive upper bound.
    Path one = tenantsDir.resolve("b/tenant.yaml");
    Files.createDirectories(one.getParent());
    Files.writeString(one, "tenant_id: b\naccount_daily_loss_pct: 1.0\n");
    assertThat(new YamlTenantRegistry(tenantsDir.toString()).get("b").getAccountDailyLossPct())
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void missingTenantYaml_returnsDefaultWithNullThreshold(@TempDir Path tenantsDir) {
    // No tenant.yaml at all => default config, null threshold => cap disabled (no throw).
    TenantConfig cfg = new YamlTenantRegistry(tenantsDir.toString()).get("ghost");

    assertThat(cfg.getAccountDailyLossThreshold()).isNull();
  }
}
