package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import com.ohmytradeagent.orchestrator.activities.WatchlistParser.Leg;
import com.ohmytradeagent.orchestrator.activities.WatchlistParser.ParseResult;
import com.ohmytradeagent.orchestrator.activities.WatchlistParser.TickerWatch;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Direct coverage of the malformed-strike SKIP path (deliverable 1 failure mode). The live {@link
 * WatchlistParser} regex only emits numeric strikes, so a malformed strike can only be exercised by
 * feeding the mapper a hand-built {@link ParseResult} — which also proves a malformed leg is
 * skipped WITHOUT dropping the well-formed legs around it.
 */
class WatchlistTriggerRowMapperTest {

  private static WatchlistMirrorPayload source() {
    WatchlistMirrorPayload p = new WatchlistMirrorPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId("watchlist-trigger-v1");
    p.setEtDate(java.time.LocalDate.of(2026, 6, 3));
    p.setAuthor("TradingTheTrend");
    p.setSourceMessageId("msg-1");
    return p;
  }

  @Test
  void malformedStrikeLegIsSkippedWithReason_otherLegsStillMapped() {
    Leg goodCall = new Leg("756", 'c', new BigDecimal("755.30"));
    Leg badPut = new Leg("not-a-number", 'p', new BigDecimal("748.00"));
    Leg goodPut = new Leg("420", 'p', new BigDecimal("424.00"));
    ParseResult parsed =
        new ParseResult(
            List.of(
                new TickerWatch("SPY", goodCall, badPut), new TickerWatch("MSFT", null, goodPut)),
            true);

    List<WatchlistTriggerLeg> legs = WatchlistTriggerRowMapper.map(source(), parsed);

    assertThat(legs).hasSize(3);
    assertThat(legs.get(0).armable()).isTrue(); // SPY call
    assertThat(legs.get(1).armable()).isFalse(); // SPY put: malformed strike -> skipped
    assertThat(legs.get(1).getSkipReason()).contains("malformed_strike");
    assertThat(legs.get(1).getTicker()).isEqualTo("SPY");
    assertThat(legs.get(1).getRightLabel()).isEqualTo("P");
    assertThat(legs.get(2).armable()).isTrue(); // MSFT put still mapped
  }

  @Test
  void nonPositiveStrikeIsSkipped() {
    Leg zeroStrike = new Leg("0", 'c', new BigDecimal("100.00"));
    ParseResult parsed = new ParseResult(List.of(new TickerWatch("SPY", zeroStrike, null)), true);

    List<WatchlistTriggerLeg> legs = WatchlistTriggerRowMapper.map(source(), parsed);

    assertThat(legs).hasSize(1);
    assertThat(legs.get(0).armable()).isFalse();
    assertThat(legs.get(0).getSkipReason()).contains("malformed_strike");
  }
}
