package com.ohmytradeagent.orchestrator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.platform.YamlStrategyRegistry;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 CI guard for PLAN-2026-07-12-watchlist-flatten-floor-and-expired-readoption: loads the
 * repository's real {@code watchlist-trigger-v1} seed config and asserts the three bounded-flatten
 * floor fields resolve NON-null with the expected values. Without these the PositionWorkflow
 * bounded-flatten path cannot resolve a floor and emits a {@code FlattenFloorConfigError} on the
 * expiry session. This mirrors {@link RepoTenantsBrokerTargetGuardTest}: it validates the actual
 * committed YAML on every {@code mvn verify}, so dropping any of the three floors fails CI.
 */
class WatchlistTriggerFloorSeedGuardTest {

  @Test
  void watchlistTriggerSeedResolvesBoundedFlattenFloors() {
    Path tenantsDir = locateRepoTenantsDir();
    // If the tree cannot be located from the test working dir, skip rather than false-fail.
    assumeTrue(tenantsDir != null, "repo tenants/ dir not found from test working directory");

    StrategyConfig cfg =
        new YamlStrategyRegistry(tenantsDir.toString()).get("dev", "watchlist-trigger-v1");

    // Plan Phase 1: the three opt-in floor fields must be present (non-null) on the seed so the
    // bounded-flatten / expiry-session flatten resolves a real floor instead of null.
    assertThat(cfg.getExitFloorAbs())
        .as("exit_floor_abs must be seeded non-null on watchlist-trigger-v1")
        .isNotNull()
        .isEqualByComparingTo(new BigDecimal("0.05"));
    assertThat(cfg.getExitFloorPct())
        .as("exit_floor_pct must be seeded non-null on watchlist-trigger-v1")
        .isNotNull()
        .isEqualByComparingTo(new BigDecimal("0.5"));
    assertThat(cfg.getExpiryDayFloor())
        .as("expiry_day_floor must be seeded non-null on watchlist-trigger-v1")
        .isNotNull()
        .isEqualByComparingTo(new BigDecimal("0.01"));
  }

  /** Walks up from the working directory to find the repo-root {@code tenants/} directory. */
  private static Path locateRepoTenantsDir() {
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 6 && dir != null; i++) {
      Path candidate = dir.resolve("tenants");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    return null;
  }
}
