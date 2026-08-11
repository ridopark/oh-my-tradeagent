package com.ohmytradeagent.tdbff.entries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ohmytradeagent.tdbff.entries.OccParser.InvalidOccException;
import com.ohmytradeagent.tdbff.entries.OccParser.ParsedOcc;
import java.time.LocalDate;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * PLAN-2026-08-10-live-manual-bto.
 *
 * <p>The load-bearing property here is the ROUND TRIP: whatever tuple this parser produces must
 * re-encode to the same OCC through {@code orchestrator/.../domain/OccSymbol.of}, because that is
 * what {@code ContractActivities.resolve} runs downstream to decide which contract the order is
 * actually for. The BFF cannot import that class (no module dependency), so {@link #reencode} below
 * is a faithful transcription of {@code OccSymbol.of}'s formatting — {@code %-6s} root padding,
 * {@code yyMMdd} expiry, and the strike in thousandths as {@code %08d}. If that encoder ever
 * changes, this test fails and both sides get fixed together.
 */
class OccParserTest {

  /** Transcription of {@code OccSymbol.of}'s encoding — see the class javadoc. */
  private static String reencode(ParsedOcc p) {
    long strikeMillis = p.strike().movePointRight(3).longValueExact();
    return String.format(
        Locale.ROOT,
        "%-6s%02d%02d%02d%s%08d",
        p.ticker(),
        p.expiry().getYear() % 100,
        p.expiry().getMonthValue(),
        p.expiry().getDayOfMonth(),
        p.right(),
        strikeMillis);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "NVDA 260821C00225000", // what an operator actually pastes (single space)
        "NVDA  260821C00225000", // the canonical padded form
        "NVDA260821C00225000", // the compact broker form
        "  nvda 260821c00225000  " // sloppy paste: surrounding space + lowercase
      })
  void parsesEveryFormOperatorsPaste(String input) {
    ParsedOcc p = OccParser.parse(input);

    assertThat(p.ticker()).isEqualTo("NVDA");
    assertThat(p.expiry()).isEqualTo(LocalDate.of(2026, 8, 21));
    assertThat(p.right()).isEqualTo("C");
    assertThat(p.strike()).isEqualByComparingTo("225");
    assertThat(p.occ()).isEqualTo("NVDA  260821C00225000");
  }

  @Test
  void strikeIsPlainNotScientific() {
    // A bare stripTrailingZeros() yields 2.25E+2, which is legal JSON but renders unreadably in the
    // OrderIntent and the audit subject. toPlainString keeps it "225".
    assertThat(OccParser.parse("NVDA 260821C00225000").strike().toPlainString()).isEqualTo("225");
  }

  @Test
  void roundTripsThroughTheCanonicalEncoderForRealContracts() {
    // Literal OCCs taken from this repo's production fixtures + tests.
    for (String occ :
        new String[] {
          "NVDA  260821C00225000",
          "NVDA  260516C00140000",
          "SPY   260706P00710000",
          "AAPL  250117C00150000",
          "A     260821C00050000" // 1-char root: the %-6s padding edge
        }) {
      assertThat(reencode(OccParser.parse(occ))).as("round trip for %s", occ).isEqualTo(occ);
    }
  }

  @Test
  void fractionalStrikeRoundTrips() {
    // Sub-dollar strikes exist (e.g. penny-stock LEAPS). The thousandths encoding must survive.
    ParsedOcc p = OccParser.parse("F     260821C00000500");
    assertThat(p.strike()).isEqualByComparingTo("0.5");
    assertThat(reencode(p)).isEqualTo("F     260821C00000500");
  }

  @Test
  void putsParse() {
    ParsedOcc p = OccParser.parse("SPY 260706P00710000");
    assertThat(p.right()).isEqualTo("P");
    assertThat(p.strike()).isEqualByComparingTo("710");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "", // blank
        "   ", // whitespace only
        "NVDA", // ticker alone
        "NVDA 260821X00225000", // right is neither C nor P
        "NVDA 260821C0022500", // 7-digit strike
        "NVDA 2608210022500012", // no right
        "TOOLONGX 260821C00225000", // root > 6 chars
        "BRK.B 260821C00225000", // dotted root — rejected here, not by a doomed workflow
        "9NVDA 260821C00225000", // numeric root: schema ticker pattern is ^[A-Z]{1,6}$
        "NVDA 261321C00225000", // month 13
        "NVDA 260832C00225000", // day 32
        "NVDA 260821C00000000" // zero strike
      })
  void rejectsMalformedInput(String input) {
    assertThatThrownBy(() -> OccParser.parse(input)).isInstanceOf(InvalidOccException.class);
  }

  @Test
  void rejectsNull() {
    assertThatThrownBy(() -> OccParser.parse(null))
        .isInstanceOf(InvalidOccException.class)
        .hasMessageContaining("required");
  }
}
