package com.ohmytradeagent.contract.identity;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Builds a clickable Yahoo Finance option link from an OCC option symbol, for use in Discord
 * trade-alert embeds. Lives in the shared {@code contract} module (next to {@link WorkflowIds})
 * because BOTH the orchestrator (signal-feed / order-failure alerts) and the exec service
 * (broker-rejection alerts) render it — keeping a single OCC-parsing seam avoids the duplicate-
 * normalization drift the plan warns about.
 *
 * <p>The OCC "21-char block" is: ticker left-aligned and trailing-space-padded to 6 chars, then
 * {@code YYMMDD}, then {@code C}/{@code P}, then strike×1000 as 8 zero-padded digits — uppercase.
 * Example: {@code NFLX 260918C00100000}.
 *
 * <p>Yahoo's quote URL is {@code https://finance.yahoo.com/quote/<OCC>/}. The OCC's padding spaces
 * are NOT valid in a clickable Discord markdown link href, so they are percent-encoded as {@code
 * %20}; the visible display text stays human-readable (single-spaced, trimmed).
 *
 * <p>NON-THROWING CONTRACT (the #295/#297 non-blocking rule): a notification must never throw. When
 * the OCC cannot be built/validated, {@link #markdown(String)} / {@link #markdownFromParts} return
 * the best available plain text (no link) instead of raising.
 */
public final class YahooOptionLink {

  private static final String QUOTE_PREFIX = "https://finance.yahoo.com/quote/";

  /**
   * Compact (space-stripped, uppercased) OCC: a 1-6 char alphanumeric root, then {@code YYMMDD},
   * then {@code C}/{@code P}, then exactly 8 strike digits. The month/day numeric sanity (a regex
   * can't express 1-12 / 1-31) is checked separately after a match.
   */
  private static final Pattern OCC = Pattern.compile("[A-Z0-9]{1,6}\\d{6}[CP]\\d{8}");

  /** The OCC root component on its own (1-6 alphanumeric), for validating the from-parts ticker. */
  private static final Pattern ROOT = Pattern.compile("[A-Z0-9]{1,6}");

  /** Collapses any run of whitespace to a single space (plain-text display rendering). */
  private static final Pattern WS = Pattern.compile("\\s+");

  private YahooOptionLink() {}

  /**
   * Builds a Discord markdown link {@code [display](href)} for a 21-char OCC option symbol (which
   * may carry the embedded padding spaces). Returns the plain (trimmed, single-spaced) symbol text
   * with no link when {@code occSymbol} is blank or not a valid OCC. Never throws.
   *
   * @param occSymbol the OCC option symbol, e.g. {@code "NFLX 260918C00100000"} or compact
   * @return a clickable markdown link, or plain text when the OCC is absent/malformed
   */
  public static String markdown(String occSymbol) {
    String normalized = normalizeOcc(occSymbol);
    if (normalized == null) {
      return displayText(occSymbol);
    }
    return link(displayFromOcc(normalized), normalized);
  }

  /**
   * Constructs the OCC from its parts and builds the Discord markdown link. Used by the
   * signal-received / signal-rejected paths, which carry ticker+expiry+strike+right but may not
   * have a resolved {@code option_symbol}. Returns plain text (a readable {@code TICKER EXP STRIKE
   * RIGHT} rendering when possible, else the bare ticker) with no link when any part is
   * missing/malformed. Never throws.
   *
   * @param ticker the underlying ticker (e.g. {@code NFLX})
   * @param yymmddOrDate either a 6-digit {@code YYMMDD} or an ISO {@code YYYY-MM-DD} expiry
   * @param right {@code 'C'}/{@code 'P'} (case-insensitive)
   * @param strike the strike price (whole dollars or with cents)
   */
  public static String markdownFromParts(
      String ticker, String yymmddOrDate, char right, Object strike) {
    String occ = buildOcc(ticker, yymmddOrDate, right, strike);
    if (occ == null) {
      return partsFallback(ticker, yymmddOrDate, right, strike);
    }
    return link(displayFromOcc(occ), occ);
  }

  /**
   * Returns the normalized 21-char (compact, no spaces) OCC for {@code occSymbol}, or {@code null}
   * when it is blank or not a valid OCC. Visible for the from-parts path to validate its own build.
   */
  static String normalizeOcc(String occSymbol) {
    if (occSymbol == null) {
      return null;
    }
    // The OCC block carries trailing padding spaces after the root; compact form drops them.
    String compact = occSymbol.replace(" ", "").toUpperCase(Locale.ROOT);
    return isValidOcc(compact) ? compact : null;
  }

  /**
   * Validates a compact (space-stripped, uppercased) OCC via {@link #OCC} plus the month/day
   * numeric sanity the pattern can't express.
   */
  private static boolean isValidOcc(String compact) {
    if (!OCC.matcher(compact).matches()) {
      return false;
    }
    int yymmddStart = compact.length() - 15;
    int mm = Integer.parseInt(compact.substring(yymmddStart + 2, yymmddStart + 4));
    int dd = Integer.parseInt(compact.substring(yymmddStart + 4, yymmddStart + 6));
    return mm >= 1 && mm <= 12 && dd >= 1 && dd <= 31;
  }

  /** Builds the compact 21-char OCC from parts, or {@code null} when any part is invalid. */
  private static String buildOcc(String ticker, String yymmddOrDate, char right, Object strike) {
    if (ticker == null || ticker.isBlank()) {
      return null;
    }
    String root = ticker.trim().toUpperCase(Locale.ROOT);
    if (!ROOT.matcher(root).matches()) {
      return null;
    }
    String yymmdd = toYymmdd(yymmddOrDate);
    if (yymmdd == null) {
      return null;
    }
    char r = Character.toUpperCase(right);
    if (r != 'C' && r != 'P') {
      return null;
    }
    String strikeDigits = strikeToEightDigits(strike);
    if (strikeDigits == null) {
      return null;
    }
    // Every part was validated above (root charset, mm/dd sanity in toYymmdd, C/P, 8-digit strike),
    // so the assembled OCC is valid by construction — no re-validation needed.
    return root + yymmdd + r + strikeDigits;
  }

  /**
   * Accepts a 6-digit {@code YYMMDD} or an ISO {@code YYYY-MM-DD}; returns a {@code YYMMDD} whose
   * month/day pass the same sanity bounds as {@link #isValidOcc}, else {@code null}.
   */
  private static String toYymmdd(String value) {
    if (value == null) {
      return null;
    }
    String v = value.trim();
    String yymmdd;
    if (v.matches("\\d{6}")) {
      yymmdd = v;
    } else if (v.matches("\\d{4}-\\d{2}-\\d{2}")) {
      yymmdd = v.substring(2, 4) + v.substring(5, 7) + v.substring(8, 10);
    } else {
      return null;
    }
    int mm = Integer.parseInt(yymmdd.substring(2, 4));
    int dd = Integer.parseInt(yymmdd.substring(4, 6));
    return (mm >= 1 && mm <= 12 && dd >= 1 && dd <= 31) ? yymmdd : null;
  }

  /** strike × 1000 as 8 zero-padded digits, or {@code null} on a malformed/over-long strike. */
  private static String strikeToEightDigits(Object strike) {
    if (strike == null) {
      return null;
    }
    BigDecimal value;
    try {
      value =
          (strike instanceof BigDecimal bd) ? bd : new BigDecimal(String.valueOf(strike).trim());
    } catch (NumberFormatException e) {
      return null;
    }
    if (value.signum() <= 0) {
      return null;
    }
    BigDecimal scaled = value.movePointRight(3);
    // A fractional remainder after ×1000 (sub-mil strike) is not representable in the 8-digit
    // field.
    if (scaled.stripTrailingZeros().scale() > 0) {
      return null;
    }
    BigInteger thousandths = scaled.toBigIntegerExact();
    if (thousandths.toString().length() > 8) {
      return null;
    }
    return String.format("%08d", thousandths);
  }

  /** Human-readable display of a (compact) OCC: {@code TICKER YYMMDDC00100000} single-spaced. */
  private static String displayFromOcc(String compact) {
    int tailStart = compact.length() - 15;
    return compact.substring(0, tailStart) + " " + compact.substring(tailStart);
  }

  /**
   * Builds a Discord markdown link. The href carries the canonical 21-char OCC — root padded with
   * trailing spaces to 6 chars — with those padding spaces percent-encoded as {@code %20} (raw
   * spaces are not clickable in a Discord markdown link). The display text stays human-readable
   * (single-spaced, no padding).
   */
  private static String link(String display, String compactOcc) {
    String href = QUOTE_PREFIX + padded21(compactOcc).replace(" ", "%20") + "/";
    return "[" + display + "](" + href + ")";
  }

  /** Re-pads a compact OCC to the canonical 21-char block (root right-padded to 6 with spaces). */
  private static String padded21(String compactOcc) {
    int tailStart = compactOcc.length() - 15;
    return String.format("%-6s", compactOcc.substring(0, tailStart))
        + compactOcc.substring(tailStart);
  }

  /**
   * Plain (no-link) display for a raw symbol: trimmed and internal padding collapsed to one space.
   */
  private static String displayText(String occSymbol) {
    if (occSymbol == null || occSymbol.isBlank()) {
      return "n/a";
    }
    return WS.matcher(occSymbol.trim()).replaceAll(" ");
  }

  /** Plain-text fallback for the from-parts path when the OCC can't be built. */
  private static String partsFallback(
      String ticker, String yymmddOrDate, char right, Object strike) {
    if (ticker == null || ticker.isBlank()) {
      return "n/a";
    }
    StringBuilder sb = new StringBuilder(ticker.trim().toUpperCase(Locale.ROOT));
    if (yymmddOrDate != null && !yymmddOrDate.isBlank()) {
      sb.append(' ').append(yymmddOrDate.trim());
    }
    if (strike != null && !String.valueOf(strike).isBlank()) {
      sb.append(' ').append(String.valueOf(strike).trim());
    }
    if (right == 'C' || right == 'P' || right == 'c' || right == 'p') {
      sb.append(Character.toUpperCase(right));
    }
    return sb.toString();
  }
}
