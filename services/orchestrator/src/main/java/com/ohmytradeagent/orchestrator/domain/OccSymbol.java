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

  /**
   * Edited-signal supersede (F1): extract the strike {@link BigDecimal} from an OCC option symbol.
   * The OCC tail is fixed-width: {@code YYMMDD}(6) + right{@code C|P}(1) + strike(8) = 15 chars;
   * the 8-digit strike encodes the dollar strike in thousandths (the {@link #of} encoder does
   * {@code strike.movePointRight(3)}), so this divides the parsed 8-digit integer by 1000 to
   * recover the dollar strike. The returned value is {@code stripTrailingZeros()} so {@code
   * 00140000} parses to {@code 140} (matching the {@code CopytradeSignalPayload.strike} canonical
   * form) rather than {@code 140.000}. Strips spaces first so both the padded canonical form and
   * the compact broker form parse. Returns {@code null} on any parse failure (null, too short,
   * non-numeric) so callers can fail-safe — a candidate whose strike cannot be parsed is simply not
   * matched.
   */
  public static BigDecimal strikeOf(String occ) {
    String compact = compact(occ);
    if (compact == null || compact.length() < 15) {
      return null;
    }
    String strike8 = compact.substring(compact.length() - 8);
    try {
      long millis = Long.parseLong(strike8);
      return BigDecimal.valueOf(millis, 3).stripTrailingZeros();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Edited-signal supersede (F1): extract the option right ({@code C} or {@code P}) from an OCC
   * option symbol. The right is the single char at {@code length-9} (immediately before the 8-digit
   * strike), per the fixed 15-char {@code YYMMDD+right+strike} tail. Strips spaces first so both
   * the padded canonical form and the compact broker form parse. Returns {@code null} when the
   * symbol is too short or the extracted char is neither {@code C} nor {@code P}, so callers can
   * fail-safe.
   */
  public static String rightOf(String occ) {
    String compact = compact(occ);
    if (compact == null || compact.length() < 15) {
      return null;
    }
    String right = compact.substring(compact.length() - 9, compact.length() - 8);
    return "C".equals(right) || "P".equals(right) ? right : null;
  }

  /**
   * Normalize any OCC — compact ({@code NVDA260706P00190000}) or already-padded ({@code NVDA
   * 260706P00190000}) — to the 21-char padded canonical form {@link #of} produces (the cache key /
   * {@code ContractSymbol} search-attribute form the PositionWorkflow seed sites register under).
   * Compacts first, then re-pads the root to 6 chars with {@code %-6s}. Idempotent ({@code
   * padded(padded(x)) == padded(x)}) and null-safe. An input too short to carry the fixed 15-char
   * {@code YYMMDD+right+strike} tail is returned unchanged (no root to pad).
   */
  public static String padded(String occ) {
    String compact = compact(occ);
    if (compact == null || compact.length() < 15) {
      return occ;
    }
    String root = compact.substring(0, compact.length() - 15);
    String tail = compact.substring(compact.length() - 15);
    return String.format(Locale.ROOT, "%-6s%s", root, tail);
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
