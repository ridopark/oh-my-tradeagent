package com.ohmytradeagent.tdbff.entries;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PLAN-2026-08-10-live-manual-bto: parse an operator-typed OCC option symbol into the {@code
 * (ticker, expiry, strike, right)} tuple {@code CopytradeSignalPayload} carries.
 *
 * <p><b>Why this is the inverse of an orchestrator class and not a call to it.</b> The canonical
 * encoder is {@code orchestrator/.../domain/OccSymbol.of}, which {@code ContractActivities.resolve}
 * uses to rebuild the OCC downstream. The BFF does not depend on the orchestrator module (see this
 * service's pom), so this is a deliberate, tested inverse rather than a new module dependency —
 * {@link OccParserTest} pins the round-trip against literal canonical OCCs taken from production
 * data. <b>If {@code OccSymbol.of}'s encoding ever changes, this class and that test must change
 * with it</b>, or a manual entry will silently address the wrong contract.
 *
 * <p>The OCC tail is fixed-width: {@code YYMMDD}(6) + right {@code C|P}(1) + 8-digit strike in
 * thousandths = 15 chars, with the underlying root leading (1-6 chars, space-padded to 6 in the
 * canonical form). Both the padded form ({@code "NVDA 260821C00225000"}) and the compact broker
 * form ({@code "NVDA260821C00225000"}) parse; whitespace is stripped first. Operators also paste a
 * single-spaced form ({@code "NVDA 260821C00225000"}), which the same strip handles.
 */
public final class OccParser {

  /**
   * Root restricted to {@code [A-Z]{1,6}} because that is exactly what {@code
   * CopytradeSignalPayload.ticker} accepts (schema pattern {@code ^[A-Z]{1,6}$}) AND what {@code
   * OccSymbol.of} accepts (1..6 chars). A numeric or dotted root would be rejected downstream by
   * schema validation, so it is rejected HERE where the operator gets a useful message instead of a
   * 500 from a doomed workflow start.
   */
  private static final Pattern COMPACT_OCC =
      Pattern.compile("^([A-Z]{1,6})(\\d{2})(\\d{2})(\\d{2})([CP])(\\d{8})$");

  private OccParser() {}

  /** The parsed contract. {@code occ} is the canonical space-padded 21-char form. */
  public record ParsedOcc(
      String occ, String ticker, LocalDate expiry, BigDecimal strike, String right) {}

  /**
   * Thrown for anything that is not a well-formed OCC. The message is safe to surface to the
   * operator — it describes the expected shape, never echoes unbounded caller input.
   */
  public static class InvalidOccException extends RuntimeException {
    public InvalidOccException(String message) {
      super(message);
    }
  }

  /**
   * Parse {@code raw} (padded, compact, or single-spaced) into its components.
   *
   * @throws InvalidOccException when the input is null/blank or does not match the OCC grammar
   */
  public static ParsedOcc parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new InvalidOccException("contract is required");
    }
    String compact = raw.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    Matcher m = COMPACT_OCC.matcher(compact);
    if (!m.matches()) {
      throw new InvalidOccException(
          "expected an OCC option symbol like 'NVDA 260821C00225000' "
              + "(1-6 letter ticker, YYMMDD expiry, C or P, 8-digit strike in thousandths)");
    }

    String ticker = m.group(1);
    int year = 2000 + Integer.parseInt(m.group(2));
    int month = Integer.parseInt(m.group(3));
    int day = Integer.parseInt(m.group(4));
    LocalDate expiry;
    try {
      expiry = LocalDate.of(year, month, day);
    } catch (java.time.DateTimeException e) {
      // The regex admits 13 as a month and 32 as a day; only the calendar can reject those.
      throw new InvalidOccException(
          "expiry is not a real date: " + m.group(2) + m.group(3) + m.group(4));
    }

    String right = m.group(5);
    // The 8 digits encode dollars in thousandths (OccSymbol.of does strike.movePointRight(3)).
    // Round-trip via toPlainString so 00225000 becomes 225 rather than 225.000 or 2.25E+2 — the
    // latter is what a bare stripTrailingZeros() yields, and while it is legal JSON it makes the
    // audit trail and the OrderIntent unreadable.
    BigDecimal strike =
        new BigDecimal(
            BigDecimal.valueOf(Long.parseLong(m.group(6)), 3).stripTrailingZeros().toPlainString());
    if (strike.signum() <= 0) {
      throw new InvalidOccException("strike must be greater than zero");
    }

    // Canonical padded form, byte-identical to what OccSymbol.of emits, so the value echoed back to
    // the operator matches what will appear in the journal, the audit, and the Holdings row.
    String occ = String.format(Locale.ROOT, "%-6s%s", ticker, compact.substring(ticker.length()));
    return new ParsedOcc(occ, ticker, expiry, strike, right);
  }
}
