// Pure OCC helpers. No imports on purpose: it runs unchanged under
// `node --experimental-strip-types --test lib/occ.test.mjs`.

/**
 * The padded 21-char OCC ("NBIS  261016C00250000") for a signal's parsed parts, or null when any
 * part is missing or malformed. Mirrors the orchestrator's OccSymbol.of, which is what contract
 * resolution uses, so the result matches the option_symbol a SignalAccepted row would carry.
 *
 * A SignalReceived subject stores every part as a String (strike may be "250", "250.0" or even
 * "2.5E+2" — BigDecimal.toString), and an absent part as "".
 */
export function occFromParts(
  ticker: unknown,
  expiry: unknown,
  strike: unknown,
  right: unknown,
): string | null {
  if (typeof ticker !== "string" || !/^[A-Z]{1,6}$/.test(ticker)) {
    return null;
  }
  if (typeof expiry !== "string") {
    return null;
  }
  const ymd = /^\d{2}(\d{2})-(\d{2})-(\d{2})$/.exec(expiry);
  if (ymd === null) {
    return null;
  }
  if (right !== "C" && right !== "P") {
    return null;
  }
  if (typeof strike !== "string" || strike.trim() === "") {
    return null;
  }
  const n = Number(strike);
  if (!Number.isFinite(n) || n <= 0) {
    return null;
  }
  const millis = Math.round(n * 1000);
  // OccSymbol.of rejects sub-1/1000 precision and >8 digits rather than rounding; so do we.
  if (Math.abs(n * 1000 - millis) > 1e-6 || millis > 99_999_999) {
    return null;
  }
  return `${ticker.padEnd(6)}${ymd[1]}${ymd[2]}${ymd[3]}${right}${String(millis).padStart(8, "0")}`;
}
