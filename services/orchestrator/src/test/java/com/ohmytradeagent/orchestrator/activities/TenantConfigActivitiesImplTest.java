package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.orchestrator.platform.TenantConfig;
import com.ohmytradeagent.orchestrator.platform.TenantRegistry;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * account-loss-cap-db epic (Phase 1): proves {@link TenantConfigActivitiesImpl} is source-agnostic
 * — it returns whatever cap the injected {@link TenantRegistry} resolves, whether that registry is
 * YAML- or DB-backed. Here we feed a stub registry standing in for a DB-sourced config and assert
 * the two cap accessors surface the seeded values verbatim. This is the seam the account kill
 * switch reads through ({@code AccountKillSwitchWorkflowImpl} calls these activities), so a green
 * here plus the unchanged {@code AccountKillSwitchWorkflowImplTest} is what makes the reader swap
 * invisible to the workflow.
 */
class TenantConfigActivitiesImplTest {

  /** A TenantRegistry standing in for the DB source: returns a fixed, pre-seeded TenantConfig. */
  private static TenantRegistry stubRegistry(BigDecimal threshold, BigDecimal pct) {
    TenantConfig cfg = new TenantConfig();
    cfg.setAccountDailyLossThreshold(threshold);
    cfg.setAccountDailyLossPct(pct);
    return new TenantRegistry() {
      @Override
      public TenantConfig get(String tenantId) {
        return cfg;
      }

      @Override
      public java.util.List<String> list() {
        return java.util.List.of("acme");
      }
    };
  }

  @Test
  void accountDailyLossThreshold_returnsRegistryValue() {
    TenantConfigActivitiesImpl activities =
        new TenantConfigActivitiesImpl(stubRegistry(new BigDecimal("5000"), null), null, null);

    assertThat(activities.accountDailyLossThreshold("dev"))
        .isEqualByComparingTo(new BigDecimal("5000"));
  }

  @Test
  void accountDailyLossPct_returnsRegistryValue() {
    TenantConfigActivitiesImpl activities =
        new TenantConfigActivitiesImpl(stubRegistry(null, new BigDecimal("0.40")), null, null);

    assertThat(activities.accountDailyLossPct("dev")).isEqualByComparingTo(new BigDecimal("0.40"));
  }

  @Test
  void missingCap_returnsNull_capInert() {
    TenantConfigActivitiesImpl activities =
        new TenantConfigActivitiesImpl(stubRegistry(null, null), null, null);

    assertThat(activities.accountDailyLossThreshold("dev")).isNull();
    assertThat(activities.accountDailyLossPct("dev")).isNull();
  }
}
