package com.ohmytradeagent.orchestrator.activities;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, dependency-free parser for the free-text daily watchlist. One play per line:
 *
 * <pre>[TICKER] STRIKE+RIGHT DIR TRIGGER   e.g.  SPY   762c  &gt;  761.00</pre>
 *
 * <p>The ticker is optional on continuation lines — a matching line with no leading ticker inherits
 * the ticker from the line above it (so {@code 753p < 754.00} belongs to the {@code SPY} above).
 * Each ticker carries at most a CALL leg (the {@code >} line) and a PUT leg (the {@code <} line).
 *
 * <p>Parsing is "clean" only when every non-blank line either matched the play grammar or was the
 * author/time header. A leftover prose line makes the result not-clean so the caller can fall back
 * to posting the raw text verbatim rather than rendering a partial table.
 */
final class WatchlistParser {

  /** Group order: 1=optional ticker, 2=strike, 3=right (c/p), 4=dir (&lt;/&gt;), 5=trigger. */
  private static final Pattern PLAY =
      Pattern.compile(
          "^\\s*([A-Z]{1,6}\\s+)?(\\d+(?:\\.\\d+)?)\\s*([cpCP])\\s*([<>])\\s*(\\d+(?:\\.\\d+)?)\\s*$");

  /**
   * A {@code <number><c|p>} strike+right token (case-insensitive), used to detect level-ish lines.
   */
  private static final Pattern STRIKE_RIGHT = Pattern.compile("\\d+(?:\\.\\d+)?[cpCP]");

  private WatchlistParser() {}

  record Leg(String strike, char right, BigDecimal trigger) {}

  record TickerWatch(String ticker, Leg call, Leg put) {}

  record ParseResult(List<TickerWatch> rows, boolean clean) {}

  static ParseResult parse(String rawText) {
    // Mutable per-ticker accumulator keyed by ticker; LinkedHashMap preserves first-seen order.
    // Each value is a 2-slot array: index 0 = call leg, index 1 = put leg.
    LinkedHashMap<String, Leg[]> byTicker = new LinkedHashMap<>();
    boolean clean = true;
    String currentTicker = null;

    if (rawText != null) {
      for (String line : rawText.split("\n", -1)) {
        if (line.isBlank()) {
          continue;
        }
        Matcher m = PLAY.matcher(line);
        if (!m.matches()) {
          // A non-level line only flips clean=false when it looks like a (malformed) level —
          // i.e. it is NOT the author header and NOT ignorable chatter. This never silently drops
          // a genuine level (a comparator / strike+right token still triggers the raw fallback).
          if (!isIgnorableChatter(line)) {
            clean = false;
          }
          continue;
        }
        String tickerGroup = m.group(1);
        if (tickerGroup != null) {
          currentTicker = tickerGroup.trim();
        }
        if (currentTicker == null) {
          // A continuation line with nothing above it to inherit — not a clean parse.
          clean = false;
          continue;
        }
        Leg[] legs = byTicker.computeIfAbsent(currentTicker, k -> new Leg[2]);
        Leg leg =
            new Leg(
                m.group(2),
                Character.toLowerCase(m.group(3).charAt(0)),
                new BigDecimal(m.group(5)));
        legs["<".equals(m.group(4)) ? 1 : 0] = leg;
      }
    }

    List<TickerWatch> rows = new ArrayList<>(byTicker.size());
    byTicker.forEach((ticker, legs) -> rows.add(new TickerWatch(ticker, legs[0], legs[1])));
    // clean iff every non-blank line was a valid level / header / ignorable chatter AND ≥1 row.
    return new ParseResult(List.copyOf(rows), clean && !rows.isEmpty());
  }

  /**
   * A trimmed non-blank line is ignorable "chatter" (does not flip {@code clean=false}) iff it is
   * the author/time header, OR it carries NEITHER a {@code >}/{@code <} comparator NOR a {@code
   * <number><c|p>} strike+right token. Greetings, emoji, sign-offs ("Good luck @everyone") are
   * ignorable; a line that DOES carry a comparator or strike+right token but did not fully parse is
   * NOT ignorable, so it still triggers the raw fallback (a genuine level is never silently
   * dropped).
   */
  private static boolean isIgnorableChatter(String line) {
    if (isAuthorHeader(line)) {
      return true;
    }
    boolean hasComparator = line.indexOf('>') >= 0 || line.indexOf('<') >= 0;
    boolean hasStrikeRight = STRIKE_RIGHT.matcher(line).find();
    return !hasComparator && !hasStrikeRight;
  }

  /**
   * Heuristic for the author/time header line (e.g. {@code "TradingTheTrend — 8:19 AM"}): a
   * non-play line that carries no {@code >}/{@code <} direction marker and looks like a byline — an
   * em-dash or an AM/PM clock token. Kept deliberately simple; a false negative just triggers the
   * raw-text fallback, which is harmless.
   */
  private static boolean isAuthorHeader(String line) {
    if (line.indexOf('>') >= 0 || line.indexOf('<') >= 0) {
      return false;
    }
    return line.contains("—") || line.contains(" AM") || line.contains(" PM");
  }
}
