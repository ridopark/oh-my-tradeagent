package com.ohmytradeagent.tdbff.preview;

import com.ohmytradeagent.tdbff.portfolio.BrokerPositionsClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The public demo's entry delay (PLAN-2026-11-26-public-staging-preview.md): a contract entered
 * less than {@code delay} ago is hidden, so the demo is not a live mirror of the signal source.
 *
 * <p>Per contract: the quarantine starts at the earliest buy order (or position entry) inside the
 * delay window, unless an open position in that contract is already older than the window — adding
 * to a position the public can already see reveals nothing new. Every row for the contract at or
 * after the quarantine start is hidden; older rows stay.
 *
 * <p>Fail-closed throughout: an open position of unknown age never anchors, and a row that cannot
 * be placed (no symbol, no time) is hidden.
 */
final class PreviewQuarantine {

  /** An open position: its contract and when it was first entered (null when unknown). */
  record OpenPos(String occ, Instant firstEntryAt) {}

  /** A journalled buy order: its contract and when it was recorded. */
  record Buy(String occ, Instant at) {}

  private PreviewQuarantine() {}

  /** Compact OCC → the instant from which that contract's rows are hidden. */
  static Map<String, Instant> hiddenFrom(
      Instant now, Duration delay, Collection<OpenPos> open, Collection<Buy> buys) {
    Instant windowStart = now.minus(delay);
    Map<String, Instant> fresh = new HashMap<>();
    Set<String> anchored = new HashSet<>();
    for (OpenPos p : open) {
      String occ = BrokerPositionsClient.compactOcc(p.occ());
      if (occ == null || p.firstEntryAt() == null) {
        continue;
      }
      if (p.firstEntryAt().isAfter(windowStart)) {
        fresh.merge(occ, p.firstEntryAt(), PreviewQuarantine::earlier);
      } else {
        anchored.add(occ);
      }
    }
    for (Buy b : buys) {
      String occ = BrokerPositionsClient.compactOcc(b.occ());
      if (occ != null && b.at() != null && b.at().isAfter(windowStart)) {
        fresh.merge(occ, b.at(), PreviewQuarantine::earlier);
      }
    }
    fresh.keySet().removeAll(anchored);
    return fresh;
  }

  /** True when a row for {@code occ} at {@code rowTime} must not be shown. */
  static boolean hides(Map<String, Instant> hiddenFrom, String occ, Instant rowTime) {
    String compact = BrokerPositionsClient.compactOcc(occ);
    if (compact == null || rowTime == null) {
      return true;
    }
    Instant from = hiddenFrom.get(compact);
    return from != null && !rowTime.isBefore(from);
  }

  private static Instant earlier(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }
}
