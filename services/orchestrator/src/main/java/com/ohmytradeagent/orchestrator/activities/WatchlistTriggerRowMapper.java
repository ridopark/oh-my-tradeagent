package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import com.ohmytradeagent.contract.WatchlistTriggerPayload;
import com.ohmytradeagent.orchestrator.activities.WatchlistParser.Leg;
import com.ohmytradeagent.orchestrator.activities.WatchlistParser.ParseResult;
import com.ohmytradeagent.orchestrator.activities.WatchlistParser.TickerWatch;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure mapping from parsed watchlist rows to {@link WatchlistTriggerLeg}s. One leg per present
 * call/put: a call maps to {@code direction=ABOVE, right=C}; a put to {@code direction=BELOW,
 * right=P} (both UPPERCASE). The free-text strike is parsed to a validated positive number; a
 * malformed strike (non-numeric or non-positive) yields a SKIP leg (payload==null, skipReason set)
 * so the caller audits-and-skips it at arm time WITHOUT dropping the other legs.
 *
 * <p>Dependency-free and deterministic. Reference: PLAN-watchlist-trigger Phase 5.
 */
final class WatchlistTriggerRowMapper {

  private WatchlistTriggerRowMapper() {}

  /** Maps every call/put leg in {@code parsed.rows()} in ticker-then-call-then-put order. */
  static List<WatchlistTriggerLeg> map(WatchlistMirrorPayload source, ParseResult parsed) {
    List<WatchlistTriggerLeg> out = new ArrayList<>();
    for (TickerWatch w : parsed.rows()) {
      if (w.call() != null) {
        out.add(mapLeg(source, w.ticker(), w.call(), WatchlistTriggerPayload.Direction.ABOVE));
      }
      if (w.put() != null) {
        out.add(mapLeg(source, w.ticker(), w.put(), WatchlistTriggerPayload.Direction.BELOW));
      }
    }
    return out;
  }

  private static WatchlistTriggerLeg mapLeg(
      WatchlistMirrorPayload source,
      String ticker,
      Leg leg,
      WatchlistTriggerPayload.Direction dir) {
    String rightLabel = dir == WatchlistTriggerPayload.Direction.ABOVE ? "C" : "P";
    BigDecimal strike = parseStrike(leg.strike());
    if (strike == null) {
      return new WatchlistTriggerLeg(null, ticker, rightLabel, "malformed_strike:" + leg.strike());
    }
    WatchlistTriggerPayload.Right right =
        dir == WatchlistTriggerPayload.Direction.ABOVE
            ? WatchlistTriggerPayload.Right.C
            : WatchlistTriggerPayload.Right.P;
    WatchlistTriggerPayload p = new WatchlistTriggerPayload();
    p.setSchemaVersion(1L);
    p.setTenantId(source.getTenantId());
    p.setStrategyId(source.getStrategyId());
    p.setTicker(ticker);
    p.setDirection(dir);
    p.setTrigger(leg.trigger());
    p.setStrike(strike);
    p.setRight(right);
    p.setAction(WatchlistTriggerPayload.Action.BTO);
    p.setEtDate(source.getEtDate());
    p.setSourceMessageId(source.getSourceMessageId());
    return new WatchlistTriggerLeg(p, ticker, rightLabel, null);
  }

  /** Free-text strike -> a positive {@link BigDecimal}, or null when malformed/non-positive. */
  private static BigDecimal parseStrike(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      BigDecimal v = new BigDecimal(raw.trim());
      return v.signum() > 0 ? v : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
