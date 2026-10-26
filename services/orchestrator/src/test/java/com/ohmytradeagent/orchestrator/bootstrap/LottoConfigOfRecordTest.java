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
 * #987 phase 2 (PLAN-2026-10-26-copytrade-lotto-v1): pins the dev {@code copytrade-lotto-v1.yaml}
 * as the config of record. The strategy needs no orchestrator code — its full-close-on-every-exit
 * and 15:30 ET expiry-day flatten are ENTIRELY carried by the field values asserted here, so a
 * drift in any of them (a {@code partial_fractions} keyword, a lead-minutes default, an EOD
 * flatten) silently changes the strategy. P0-3 copies this blob into the {@code paper_lotto}
 * tenant's DB row and P0-4 re-checks the same invariants on the stored row.
 */
class LottoConfigOfRecordTest {

  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-lotto-v1";

  @Test
  void lottoConfigOfRecord_pinsEveryInvariant() {
    Path tenantsDir = locateRepoTenantsDir();
    assumeTrue(tenantsDir != null, "repo tenants/ dir not found from test working directory");

    StrategyConfig c = new YamlStrategyRegistry(tenantsDir.toString()).get(TENANT, STRATEGY);

    // Identity / routing / source.
    assertThat(c.getStrategyId()).isEqualTo(STRATEGY);
    assertThat(c.getBrokerTarget()).isEqualTo(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    assertThat(c.getAuthorWhitelist()).containsExactly("TradingTheTrend");

    // F1: config-only full close. The matcher falls through to the default on a null/empty map, so
    // BOTH halves must hold — a 1.0 default with any keyword present is a partial again (#600).
    assertThat(c.getDefaultStcFraction()).isEqualByComparingTo("1.0");
    assertThat(c.getPartialFractions())
        .as("partial_fractions must be absent/empty — any keyword re-enables partial sizing")
        .satisfiesAnyOf(m -> assertThat(m).isNull(), m -> assertThat(m).isEmpty());
    assertThat(c.getMinPartialQtyBehavior())
        .isEqualTo(StrategyConfig.MinPartialQtyBehavior.FULL_CLOSE);
    assertThat(c.getStcIntentEnforce()).as("stc_intent_enforce must be absent").isNull();
    assertThat(c.getTrailOnPartial()).isFalse();

    // Exit timing: no EOD flatten (mirror fidelity), expiry-day flatten at exactly 15:30 ET.
    assertThat(c.getEodForceFlatten()).isFalse();
    assertThat(c.getForceClose0dteEt()).isEqualTo("15:30");
    assertThat(c.getFlattenLeadMinutes()).isZero();

    // Sizing + cap.
    assertThat(c.getCapitalSource()).isEqualTo(StrategyConfig.CapitalSource.ACCOUNT_EQUITY);
    assertThat(c.getCapitalWeight())
        .isGreaterThanOrEqualTo(new BigDecimal("0.01"))
        .isLessThanOrEqualTo(new BigDecimal("0.03"));
    assertThat(c.getMinContracts()).isEqualTo(1L);
    assertThat(c.getMaxContracts()).isEqualTo(50L);
    assertThat(c.getMaxPositions()).isEqualTo(3L);
    assertThat(c.getSkipAvg()).isTrue();

    // Age gates are P0-1 placeholders copied from copytrade-v1 — present, not yet measured.
    assertThat(c.getMaxSignalAgeBtoSecs()).isNotNull();
    assertThat(c.getMaxSignalAgeStcSecs()).isNotNull();

    // Floors, TTLs and reprice copied verbatim from copytrade-v1.
    assertThat(c.getExitFloorAbs()).isEqualByComparingTo("0.05");
    assertThat(c.getExitFloorPct()).isEqualByComparingTo("0.5");
    assertThat(c.getExpiryDayFloor()).isEqualByComparingTo("0.01");
    assertThat(c.getPendingTtlPaperSecs()).isEqualTo(90L);
    assertThat(c.getPendingTtlLiveSecs()).isEqualTo(30L);
    assertThat(c.getExitRepriceSteps()).isEqualTo(3L);
    assertThat(c.getExitRepriceTick()).isEqualByComparingTo("0.05");
    assertThat(c.getMaxSlippageAbs()).isEqualByComparingTo("0.05");
    assertThat(c.getMaxSlippagePct()).isEqualByComparingTo("0.05");

    // Dead per-strategy breaker intentionally omitted.
    assertThat(c.getDailyLossThreshold()).isNull();
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
