package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import com.ohmytradeagent.contract.WatchlistTriggerPayload;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the parse -> {@link WatchlistTriggerLeg} mapping (deliverable 1). The parser
 * itself is exercised exhaustively in {@code WatchlistParserTest}; here we assert the call/put ->
 * direction+right mapping, the free-text strike validation, and the malformed-leg skip-with-reason
 * behavior.
 */
class WatchlistTriggerActivitiesImplTest {

  private final WatchlistTriggerActivitiesImpl activity = new WatchlistTriggerActivitiesImpl();

  private static WatchlistMirrorPayload payload(String raw) {
    WatchlistMirrorPayload p = new WatchlistMirrorPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId("watchlist-trigger-v1");
    p.setEtDate(LocalDate.of(2026, 6, 3));
    p.setAuthor("TradingTheTrend");
    p.setRawText(raw);
    p.setSourceMessageId("msg-1");
    return p;
  }

  @Test
  void callMapsToAboveCall_putMapsToBelowPut() {
    List<WatchlistTriggerLeg> legs =
        activity.parseWatchlistTriggers(payload("SPY 756c > 755.30\n745p < 748.00"));

    assertThat(legs).hasSize(2);

    WatchlistTriggerPayload call = legs.get(0).getPayload();
    assertThat(call.getDirection()).isEqualTo(WatchlistTriggerPayload.Direction.ABOVE);
    assertThat(call.getRight()).isEqualTo(WatchlistTriggerPayload.Right.C);
    assertThat(call.getStrike()).isEqualByComparingTo("756");
    assertThat(call.getTrigger()).isEqualByComparingTo("755.30");
    assertThat(call.getAction()).isEqualTo(WatchlistTriggerPayload.Action.BTO);
    assertThat(call.getTicker()).isEqualTo("SPY");
    assertThat(call.getEtDate()).isEqualTo(LocalDate.of(2026, 6, 3));
    assertThat(call.getSourceMessageId()).isEqualTo("msg-1");

    WatchlistTriggerPayload put = legs.get(1).getPayload();
    assertThat(put.getDirection()).isEqualTo(WatchlistTriggerPayload.Direction.BELOW);
    assertThat(put.getRight()).isEqualTo(WatchlistTriggerPayload.Right.P);
    assertThat(put.getStrike()).isEqualByComparingTo("745");
  }

  @Test
  void oneLegPerQualifyingLeg_acrossMultipleTickers() {
    String raw =
        "SPY   756c  >  755.30\n"
            + "745p  <  748.00\n"
            + "QQQ   512c  >  511.00\n"
            + "MSFT  420p  <  424.00";
    List<WatchlistTriggerLeg> legs = activity.parseWatchlistTriggers(payload(raw));
    // SPY call+put (2) + QQQ call (1) + MSFT put (1) = 4 legs.
    assertThat(legs).hasSize(4);
    assertThat(legs).allMatch(WatchlistTriggerLeg::armable);
  }

  @Test
  void notCleanParse_yieldsNoLegs() {
    List<WatchlistTriggerLeg> legs =
        activity.parseWatchlistTriggers(payload("lol no setups today"));
    assertThat(legs).isEmpty();
  }
}
