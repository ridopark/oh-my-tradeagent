package com.ohmytradeagent.tdbff.preview;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.tdbff.preview.PreviewQuarantine.Buy;
import com.ohmytradeagent.tdbff.preview.PreviewQuarantine.OpenPos;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PreviewQuarantineTest {

  private static final Instant NOW = Instant.parse("2026-11-27T15:00:00Z");
  private static final Duration DELAY = Duration.ofMinutes(15);
  private static final String OCC = "NVDA261224C00200000";

  private static Instant minutesAgo(long m) {
    return NOW.minus(Duration.ofMinutes(m));
  }

  private static Map<String, Instant> hiddenFrom(List<OpenPos> open, List<Buy> buys) {
    return PreviewQuarantine.hiddenFrom(NOW, DELAY, open, buys);
  }

  @Test
  void freshEntry_hidesTheContractFromTheFirstBuy() {
    Map<String, Instant> h =
        hiddenFrom(
            List.of(new OpenPos(OCC, minutesAgo(4))),
            List.of(new Buy(OCC, minutesAgo(5)), new Buy(OCC, minutesAgo(3))));

    assertThat(h).containsEntry(OCC, minutesAgo(5));
  }

  @Test
  void buyOrderWithNoPositionYet_isAlreadyHidden() {
    // The order is journalled before a PositionWorkflow exists — the order alone must hide it.
    assertThat(hiddenFrom(List.of(), List.of(new Buy(OCC, minutesAgo(1)))))
        .containsEntry(OCC, minutesAgo(1));
  }

  @Test
  void entryOlderThanTheDelay_isVisible() {
    assertThat(
            hiddenFrom(
                List.of(new OpenPos(OCC, minutesAgo(16))), List.of(new Buy(OCC, minutesAgo(16)))))
        .isEmpty();
  }

  @Test
  void entryExactlyAtTheDelay_isVisible() {
    assertThat(
            hiddenFrom(
                List.of(new OpenPos(OCC, minutesAgo(15))), List.of(new Buy(OCC, minutesAgo(15)))))
        .isEmpty();
  }

  @Test
  void addingToAnAlreadyVisiblePosition_showsImmediately() {
    assertThat(
            hiddenFrom(
                List.of(new OpenPos(OCC, minutesAgo(40))), List.of(new Buy(OCC, minutesAgo(2)))))
        .isEmpty();
  }

  @Test
  void quickOpenAndClose_staysHiddenUntilTheDelayHasPassed() {
    // Position already closed (no open position), entry 5 minutes ago.
    assertThat(hiddenFrom(List.of(), List.of(new Buy(OCC, minutesAgo(5)))))
        .containsEntry(OCC, minutesAgo(5));
  }

  @Test
  void reEntryAfterTheOldPositionClosed_isHidden_evenThoughTheContractWasShownBefore() {
    // The earlier position is gone; only the 2-minute-old one is open, so nothing anchors it.
    assertThat(
            hiddenFrom(
                List.of(new OpenPos(OCC, minutesAgo(2))), List.of(new Buy(OCC, minutesAgo(2)))))
        .containsEntry(OCC, minutesAgo(2));
  }

  @Test
  void openPositionOfUnknownAge_doesNotAnchorAFreshBuy() {
    // Fail-closed: an unknown entry time cannot be used to reveal a fresh entry.
    assertThat(hiddenFrom(List.of(new OpenPos(OCC, null)), List.of(new Buy(OCC, minutesAgo(2)))))
        .containsEntry(OCC, minutesAgo(2));
  }

  @Test
  void openPositionOfUnknownAge_withNoRecentBuy_isVisible() {
    // An adopted position has no entry event and no order; it is not a signal to protect.
    assertThat(hiddenFrom(List.of(new OpenPos(OCC, null)), List.of())).isEmpty();
  }

  @Test
  void youngPositionWithNoJournalledBuy_isHiddenFromItsEntryTime() {
    assertThat(hiddenFrom(List.of(new OpenPos(OCC, minutesAgo(6))), List.of()))
        .containsEntry(OCC, minutesAgo(6));
  }

  @Test
  void contractsAreIndependent() {
    String other = "GOOGL261204C00285000";
    Map<String, Instant> h =
        hiddenFrom(
            List.of(new OpenPos(other, minutesAgo(60))),
            List.of(new Buy(OCC, minutesAgo(1)), new Buy(other, minutesAgo(1))));

    assertThat(h).containsOnlyKeys(OCC);
  }

  @Test
  void spacedAndCompactSymbols_areTheSameContract() {
    Map<String, Instant> h =
        hiddenFrom(
            List.of(new OpenPos("NVDA  261224C00200000", minutesAgo(40))),
            List.of(new Buy(OCC, minutesAgo(2))));

    assertThat(h).isEmpty();
  }

  @Test
  void rowIsHidden_onlyAtOrAfterTheQuarantineStart() {
    Map<String, Instant> h = Map.of(OCC, minutesAgo(5));

    assertThat(PreviewQuarantine.hides(h, OCC, minutesAgo(5))).isTrue();
    assertThat(PreviewQuarantine.hides(h, OCC, minutesAgo(1))).isTrue();
    assertThat(PreviewQuarantine.hides(h, "NVDA  261224C00200000", minutesAgo(1))).isTrue();
    assertThat(PreviewQuarantine.hides(h, OCC, minutesAgo(6))).isFalse();
    assertThat(PreviewQuarantine.hides(h, "GOOGL261204C00285000", minutesAgo(1))).isFalse();
  }

  @Test
  void rowWithNoTimeOrNoSymbol_isHidden() {
    // Fail-closed: a row that cannot be placed is never shown.
    Map<String, Instant> h = Map.of(OCC, minutesAgo(5));

    assertThat(PreviewQuarantine.hides(h, OCC, null)).isTrue();
    assertThat(PreviewQuarantine.hides(h, null, minutesAgo(30))).isTrue();
    assertThat(PreviewQuarantine.hides(Map.of(), null, minutesAgo(30))).isTrue();
  }
}
