package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.contract.StrategyConfig;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for PLAN-2026-07-09: the watchlist-only fields ({@code entry_mode}, {@code
 * gap_tolerance_pct}) are opt-in and null-when-absent, so a copytrade StrategyConfig that never
 * sets them must NOT carry them in its canonical config. Before the fix they defaulted in the
 * schema and leaked into copytrade's canonical config (and the {@code /config} editor). The
 * universal {@code enabled} field keeps its default and stays present.
 */
class TenantConfigSnapshotTest {

  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void canonicalize_copytradeConfigWithoutWatchlistFields_omitsThemButKeepsEnabled() {
    StrategyConfig config = new StrategyConfig();
    config.setSchemaVersion(1L);
    config.setTenantId("dev");
    config.setStrategyId("copytrade-v1");
    config.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    config.setCapitalWeight(new BigDecimal("0.2"));
    config.setMinContracts(1L);
    config.setMaxContracts(50L);
    // The watchlist-only fields (entry_mode / gap_tolerance_pct / equity_emit_delta_pct)
    // deliberately left unset (null) — a copytrade config never sets them.

    Map<String, Object> canonical = TenantConfigSnapshot.canonicalize(objectMapper, config);

    assertThat(canonical)
        .doesNotContainKeys("entry_mode", "gap_tolerance_pct", "equity_emit_delta_pct");
    assertThat(canonical).containsEntry("enabled", true);
  }
}
