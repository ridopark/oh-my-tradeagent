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

  /**
   * Extract the underlying ticker (root) from an OCC option symbol. The 21-char OCC form is {@code
   * <root padded to 6 with %-6s><yyMMdd><C|P><8-digit strike>}; the root is the leading 6 chars
   * with the {@code %-6s} space-padding stripped. Accepts the compact (unpadded) broker form too —
   * it strips spaces first, then takes everything before the 6-digit expiry / right / strike tail.
   * Null-safe: returns {@code null} for {@code null} input.
   */
  public static String underlying(String occ) {
    if (occ == null) {
      return null;
    }
    String compact = occ.replace(" ", "");
    // The fixed tail is yyMMdd (6) + right (1) + strike (8) = 15 chars; the root is the remainder.
    if (compact.length() <= 15) {
      return compact;
    }
    return compact.substring(0, compact.length() - 15);
  }

  /**
   * Issue #434: inverse of {@link #of} — extract the expiry {@link LocalDate} from an OCC option
   * symbol. The OCC tail is fixed-width: {@code YYMMDD}(6) + right{@code C|P}(1) + strike(8) = 15
   * chars, with the underlying root leading (variable, space-padded to 6 in the {@link #of}
   * canonical form). Strips spaces first so both the padded canonical form and the compact broker
   * form parse, then reads the 6-digit {@code YYMMDD} at {@code length-15}. Returns {@code null} on
   * any parse failure (null, too short, non-numeric, invalid date) so callers can fail-safe.
   */
  public static LocalDate expiryOf(String occ) {
    if (occ == null) {
      return null;
    }
    String compact = occ.replace(" ", "");
    if (compact.length() < 15) {
      return null;
    }
    String yymmdd = compact.substring(compact.length() - 15, compact.length() - 9);
    try {
      int yy = Integer.parseInt(yymmdd.substring(0, 2));
      int mm = Integer.parseInt(yymmdd.substring(2, 4));
      int dd = Integer.parseInt(yymmdd.substring(4, 6));
      return LocalDate.of(2000 + yy, mm, dd);
    } catch (RuntimeException e) {
      return null;
    }
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
