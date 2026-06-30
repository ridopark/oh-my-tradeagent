package com.ohmytradeagent.orchestrator.platform;

import static org.assertj.core.api.Assertions.assertThat;

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

    TenantConfig cfg = new YamlTenantRegistry(tenantsDir).get("dev");

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

    TenantConfig cfg = new YamlTenantRegistry(tenantsDir).get("dev");

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

    TenantConfig cfg = new YamlTenantRegistry(tenantsDir).get("dev");

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

    TenantConfig cfg = new YamlTenantRegistry(tenantsDir).get("dev");

    assertThat(cfg.getAccountDailyLossPct()).isNull();
    assertThat(cfg.getAccountDailyLossThreshold()).isEqualByComparingTo(new BigDecimal("1500"));
  }

  @Test
  void missingTenantYaml_returnsDefaultWithNullThreshold(@TempDir Path tenantsDir) {
    // No tenant.yaml at all => default config, null threshold => cap disabled (no throw).
    TenantConfig cfg = new YamlTenantRegistry(tenantsDir).get("ghost");

    assertThat(cfg.getAccountDailyLossThreshold()).isNull();
  }
}
