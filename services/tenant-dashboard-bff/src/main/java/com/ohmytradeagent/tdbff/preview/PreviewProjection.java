package com.ohmytradeagent.tdbff.preview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ohmytradeagent.contract.PortfolioHistoryResult;
import com.ohmytradeagent.tdbff.portfolio.PortfolioReturnCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Allow-list projections for the public demo. Every method builds its output by copying NAMED
 * fields out of the tenant read; nothing is passed through wholesale, so a field added to a source
 * row later is not exposed until it is named here. Applies the strategy filter and the entry delay
 * ({@link PreviewQuarantine}) as it goes.
 *
 * <p>Never copied: the broker account number, workflow / signal / correlation / event ids and
 * intent keys (they embed Discord channel and message ids), the audit actor (can be an operator
 * email), free-text fields ({@code last_error}, kill-switch reason), and the tenant id.
 */
final class PreviewProjection {

  /** The strategies the demo shows. {@code gated_condor} is deliberately absent. */
  static final Set<String> SHOWN_STRATEGIES =
      Set.of("copytrade-v1", "copytrade-lotto-v1", "watchlist-trigger-v1");

  private static final List<String> POSITION_FIELDS =
      List.of(
          "strategy_id",
          "contract_symbol",
          "remaining_qty",
          "entry_premium",
          "open_notional",
          "underlying_spot_entry",
          "underlying_price",
          // NOT entry_qty / realized_pl: the tenant read sums them per entry signal id, which
          // every leg of a watchlist shares, so a visible leg's figures would move when a hidden
          // sibling fills or exits.
          "trailing_armed",
          "current_price",
          "unrealized_pl",
          "unrealized_intraday_pl",
          "lastday_price",
          "trail_giveback_pct",
          "trail_stop_price");
  private static final List<String> EQUITY_FIELDS =
      List.of("broker_target", "equity", "last_equity", "today_pl");
  private static final List<String> FILL_SUBJECT_FIELDS =
      List.of(
          "option_symbol",
          "filled_qty",
          "qty_filled",
          "avg_fill_price",
          "remaining_qty_after",
          "broker_order_id");
  private static final List<String> ORDER_FIELDS =
      List.of(
          "strategy_id",
          "broker_target",
          "option_symbol",
          "side",
          "qty",
          "limit_price",
          "state",
          "broker_order_id",
          "recorded_at",
          "submitted_at",
          "filled_qty",
          "avg_fill_price",
          "filled_at");
  private static final List<String> MARK_FIELDS =
      List.of("bid", "mid", "ask", "quote_at", "polled_at", "warming");
  private static final List<String> UNDERLYING_FIELDS = List.of("ticker", "price", "at");

  private static final java.util.regex.Pattern BROKER_ORDER_ID =
      java.util.regex.Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final ObjectMapper JSON = new ObjectMapper();

  private PreviewProjection() {}

  /**
   * The {@code /api/portfolio} body, reduced to the demo's fields and visible positions.
   *
   * <p>Account totals (equity, realized P&L) come from {@code totals}, an earlier reading of the
   * same body, never from {@code body}: they move the moment a hidden entry fills, so publishing
   * the current ones would announce it. {@code null} publishes none.
   */
  static Map<String, Object> portfolio(
      Map<String, Object> body, Map<String, Instant> hiddenFrom, Map<String, Object> totals) {
    List<Map<String, Object>> positions = new ArrayList<>();
    BigDecimal notional = BigDecimal.ZERO;
    for (Map<String, Object> p : rows(body.get("open_positions"))) {
      if (!shown(p) || hiddenFrom.containsKey(compact(p.get("contract_symbol")))) {
        continue;
      }
      positions.add(copy(p, POSITION_FIELDS));
      if (p.get("open_notional") != null) {
        notional = notional.add(new BigDecimal(String.valueOf(p.get("open_notional"))));
      }
    }
    List<Map<String, Object>> equity = new ArrayList<>();
    for (Map<String, Object> e : rows(totals == null ? null : totals.get("account_equity"))) {
      equity.add(copy(e, EQUITY_FIELDS));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("trading_day", body.get("trading_day"));
    out.put("open_positions", positions);
    out.put("open_positions_degraded", Boolean.TRUE.equals(body.get("open_positions_degraded")));
    out.put("open_positions_count", positions.size());
    out.put("sum_open_notional", notional);
    out.put("realized_pnl_today", totals == null ? null : totals.get("realized_pnl_today"));
    out.put("realized_pnl_all_time", totals == null ? null : totals.get("realized_pnl_all_time"));
    out.put("account_equity", equity);
    return out;
  }

  /**
   * {@code /api/trades} rows as demo fills. {@code subject} stays a JSON string (the dashboard's
   * fill renderer parses it) but carries only the named fill fields.
   */
  static List<Map<String, Object>> fills(
      List<Map<String, Object>> rows,
      Map<String, Instant> hiddenFrom,
      Map<String, BigDecimal> entryBasis) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> r : rows) {
      ObjectNode subject = fillSubject(r.get("subject"));
      if (!shown(r) || subject == null) {
        continue;
      }
      String occ = subject.path("option_symbol").asText(null);
      if (PreviewQuarantine.hides(hiddenFrom, occ, instant(r.get("occurred_at")))) {
        continue;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("occurred_at", r.get("occurred_at"));
      m.put("kind", r.get("kind"));
      m.put("strategy_id", r.get("strategy_id"));
      m.put("subject", subject.toString());
      // NOT the tenant read's entry_basis: that one blends every contract sharing the row's
      // correlation id, so a hidden watchlist leg filling would move a visible row's figure.
      m.put(
          "entry_basis",
          r.get("correlation_id") instanceof String id
              ? entryBasis.get(PreviewEntryTimes.key(id, occ))
              : null);
      out.add(m);
    }
    return out;
  }

  /**
   * Weighted entry cost per (entry signal id, contract), from entry fills that are themselves
   * visible. A fill inside its contract's quarantine contributes nothing, so a hidden entry can
   * never be read off a visible row's basis.
   */
  static Map<String, BigDecimal> entryBasis(
      List<PreviewEntryTimes.EntryFill> entries, Map<String, Instant> hiddenFrom) {
    Map<String, BigDecimal> cost = new LinkedHashMap<>();
    Map<String, Long> qty = new LinkedHashMap<>();
    for (PreviewEntryTimes.EntryFill e : entries) {
      if (e.signalId() == null || PreviewQuarantine.hides(hiddenFrom, e.occ(), e.at())) {
        continue;
      }
      String key = PreviewEntryTimes.key(e.signalId(), e.occ());
      cost.merge(key, e.price().multiply(BigDecimal.valueOf(e.qty())), BigDecimal::add);
      qty.merge(key, e.qty(), Long::sum);
    }
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    cost.forEach(
        (k, c) ->
            out.put(
                k, c.divide(BigDecimal.valueOf(qty.get(k)), 6, java.math.RoundingMode.HALF_UP)));
    return out;
  }

  /** {@code /api/orders} rows; {@code last_error} becomes a bare {@code failed} flag. */
  static List<Map<String, Object>> orders(
      List<Map<String, Object>> rows, Map<String, Instant> hiddenFrom, Instant since) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> r : rows) {
      Instant at = instant(r.get("recorded_at"));
      if (!shown(r)
          || at == null
          || at.isBefore(since)
          || PreviewQuarantine.hides(hiddenFrom, (String) r.get("option_symbol"), at)) {
        continue;
      }
      Map<String, Object> m = copy(r, ORDER_FIELDS);
      if (!brokerOrderId(m.get("broker_order_id"))) {
        m.remove("broker_order_id");
      }
      Object err = r.get("last_error");
      m.put("failed", err != null && !String.valueOf(err).isBlank());
      out.add(m);
    }
    return out;
  }

  /** One market-data mark, re-keyed to the row-form symbol the positions table shows. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> mark(Map<String, Object> mark, String rowFormOcc) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("occ", rowFormOcc);
    out.putAll(copy(mark, MARK_FIELDS));
    if (mark.get("underlying") instanceof Map<?, ?> u) {
      out.put("underlying", copy((Map<String, Object>) u, UNDERLYING_FIELDS));
    }
    return out;
  }

  /** Coerces the timestamp shapes the readers return; null when it is none of them. */
  static Instant instant(Object v) {
    if (v instanceof Instant i) {
      return i;
    }
    if (v instanceof OffsetDateTime o) {
      return o.toInstant();
    }
    if (v instanceof java.util.Date d) {
      return d.toInstant();
    }
    return null;
  }

  static String compact(Object occ) {
    return occ instanceof String s
        ? com.ohmytradeagent.tdbff.portfolio.BrokerPositionsClient.compactOcc(s)
        : null;
  }

  /** Broker order ids are UUIDs. Anything else in that field is not an id and is not published. */
  private static boolean brokerOrderId(Object v) {
    return v instanceof String s && BROKER_ORDER_ID.matcher(s).matches();
  }

  private static boolean shown(Map<String, Object> row) {
    return row.get("strategy_id") instanceof String s && SHOWN_STRATEGIES.contains(s);
  }

  private static ObjectNode fillSubject(Object raw) {
    if (!(raw instanceof String s)) {
      return null;
    }
    try {
      JsonNode node = JSON.readTree(s);
      if (!node.isObject()) {
        return null;
      }
      ObjectNode out = JSON.createObjectNode();
      for (String f : FILL_SUBJECT_FIELDS) {
        JsonNode v = node.get(f);
        // Scalars only: a nested object under an allowed name could carry anything.
        if (v != null
            && v.isValueNode()
            && (!f.equals("broker_order_id") || brokerOrderId(v.asText()))) {
          out.set(f, v);
        }
      }
      return out;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return null;
    }
  }

  /** Seconds one bar covers, by the broker's timeframe name. Anything else is not published. */
  private static final Map<String, Long> BAR_SECONDS =
      Map.of("5Min", 300L, "15Min", 900L, "1D", 86_400L);

  /**
   * One range of the account's equity history for the demo chart, or null when there is nothing
   * publishable (no points, or a timeframe this code does not know).
   *
   * <p>Only bars that ENDED at least {@code delay} ago are kept. A bar still in progress carries
   * the account's value as of now, whatever its timestamp says (today's daily bar most of all), so
   * keeping it would publish live equity and announce an entry inside its hold-back. The range
   * return is recomputed from the kept bars alone, never from a live equity read. Figures are
   * rounded (cents, and 6 decimals for ratios): the chart needs no more.
   */
  static Map<String, Object> history(
      PortfolioHistoryResult r, Instant now, Duration delay, PortfolioReturnCalculator calculator) {
    Long barSeconds =
        r == null || r.getTimeframe() == null ? null : BAR_SECONDS.get(r.getTimeframe());
    List<Long> ts = r == null ? null : r.getTimestamps();
    List<BigDecimal> eq = r == null ? null : r.getEquity();
    if (barSeconds == null || ts == null || eq == null) {
      return null;
    }
    long cutoff = now.minus(delay).getEpochSecond();
    List<Long> timestamps = new ArrayList<>();
    List<BigDecimal> equity = new ArrayList<>();
    List<BigDecimal> pl = new ArrayList<>();
    List<BigDecimal> plPct = new ArrayList<>();
    for (int i = 0; i < ts.size() && i < eq.size(); i++) {
      if (ts.get(i) == null || eq.get(i) == null || ts.get(i) + barSeconds > cutoff) {
        continue;
      }
      timestamps.add(ts.get(i));
      equity.add(eq.get(i));
      pl.add(at(r.getProfitLoss(), i));
      plPct.add(at(r.getProfitLossPct(), i));
    }
    if (timestamps.isEmpty()) {
      return null;
    }
    PortfolioReturnCalculator.RangeReturn rr =
        calculator.compute(
            equity,
            r.getBaseValue(),
            timestamps,
            r.getCashFlowTimestamps(),
            r.getCashFlowAmounts(),
            r.getCashFlowsAvailable(),
            r.getBaseValueAsof(),
            null,
            null);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("timestamps", timestamps);
    out.put("equity", scaled(equity, 2));
    out.put("profit_loss", scaled(pl, 2));
    out.put("profit_loss_pct", scaled(plPct, 6));
    out.put("base_value", scale(r.getBaseValue(), 2));
    out.put("base_value_asof", r.getBaseValueAsof());
    out.put("timeframe", r.getTimeframe());
    out.put("range_pl", scale(rr.rangePl(), 2));
    out.put("range_pl_pct", scale(rr.rangePlPct(), 6));
    out.put("cash_flows_available", Boolean.TRUE.equals(r.getCashFlowsAvailable()));
    return out;
  }

  /** The i-th entry, or zero where the broker sent a shorter or sparse list. */
  private static BigDecimal at(List<BigDecimal> list, int i) {
    BigDecimal v = list != null && i < list.size() ? list.get(i) : null;
    return v == null ? BigDecimal.ZERO : v;
  }

  private static BigDecimal scale(BigDecimal v, int places) {
    return v == null ? null : v.setScale(places, RoundingMode.HALF_UP);
  }

  private static List<BigDecimal> scaled(List<BigDecimal> values, int places) {
    List<BigDecimal> out = new ArrayList<>(values.size());
    for (BigDecimal v : values) {
      out.add(scale(v, places));
    }
    return out;
  }

  private static Map<String, Object> copy(Map<String, Object> src, List<String> fields) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (String f : fields) {
      if (src.containsKey(f)) {
        out.put(f, src.get(f));
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> rows(Object v) {
    return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
  }
}
