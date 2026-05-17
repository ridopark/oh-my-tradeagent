package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.StrategyConfig;
import java.util.Map;

/**
 * Issue #6: ticker → sector resolver consulted by the {@code sector_concentration_cap} gate.
 *
 * <p>Default lookup is the per-strategy {@code sector_overrides} map. Tickers without an override
 * resolve to {@link #UNKNOWN_SECTOR} and are exempt from the cap (the gate treats "unknown" as a
 * sentinel so a strategy with no sector_overrides config never inadvertently blocks entries).
 */
@FunctionalInterface
public interface SectorResolver {

  String UNKNOWN_SECTOR = "unknown";

  String resolve(String tickerSymbol, StrategyConfig config);

  /** Default config-backed resolver; null-safe and case-preserving. */
  SectorResolver CONFIG_BACKED =
      (ticker, config) -> {
        if (ticker == null || config == null) {
          return UNKNOWN_SECTOR;
        }
        Map<String, String> overrides = config.getSectorOverrides();
        if (overrides == null || overrides.isEmpty()) {
          return UNKNOWN_SECTOR;
        }
        String sector = overrides.get(ticker);
        return sector == null || sector.isBlank() ? UNKNOWN_SECTOR : sector;
      };
}
