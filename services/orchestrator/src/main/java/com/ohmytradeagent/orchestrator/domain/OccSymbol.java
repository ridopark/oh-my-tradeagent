package com.ohmytradeagent.orchestrator.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public record OccSymbol(String value) {

  private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

  /**
   * Strip the OCC root space-padding for padding-agnostic comparison. The padded 21-char {@link
   * #of} form pads the root to 6 chars with {@code %-6s}; the broker/audit *compact* form (e.g.
   * Alpaca) carries no padding. Comparing the space-stripped forms makes the two equivalent.
   * Null-safe: returns {@code null} for a {@code null} input (the exact semantics of the inlined
   * {@code occ == null ? null : occ.replace(" ", "")} idiom it replaces).
   */
  public static String compact(String s) {
    return s == null ? null : s.replace(" ", "");
  }

  public static OccSymbol of(String root, LocalDate expiry, BigDecimal strike, String right) {
    if (root == null || root.isBlank() || root.length() > 6) {
      throw new IllegalArgumentException("root must be 1..6 chars, got: " + root);
    }
    if (!"C".equals(right) && !"P".equals(right)) {
      throw new IllegalArgumentException("right must be C or P, got: " + right);
    }
    if (expiry == null) {
      throw new IllegalArgumentException("expiry is required");
    }
    if (strike == null || strike.signum() <= 0) {
      throw new IllegalArgumentException("strike must be > 0, got: " + strike);
    }
    long strikeMillis;
    try {
      strikeMillis = strike.movePointRight(3).longValueExact();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("strike has finer precision than 1/1000: " + strike, e);
    }
    if (strikeMillis > 99_999_999L) {
      throw new IllegalArgumentException("strike overflows 8 digits: " + strike);
    }
    String paddedRoot = String.format(Locale.ROOT, "%-6s", root.toUpperCase(Locale.ROOT));
    String yymmdd = expiry.format(YYMMDD);
    return new OccSymbol(
        String.format(Locale.ROOT, "%s%s%s%08d", paddedRoot, yymmdd, right, strikeMillis));
  }
}
