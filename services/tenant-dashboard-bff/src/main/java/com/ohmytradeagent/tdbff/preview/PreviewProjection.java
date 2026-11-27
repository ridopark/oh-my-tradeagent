package com.ohmytradeagent.tdbff.preview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
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

  /** The {@code /api/portfolio} body, reduced to the demo's fields and visible positions. */
  static Map<String, Object> portfolio(Map<String, Object> body, Map<String, Instant> hiddenFrom) {
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
    for (Map<String, Object> e : rows(body.get("account_equity"))) {
      equity.add(copy(e, EQUITY_FIELDS));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("trading_day", body.get("trading_day"));
    out.put("open_positions", positions);
    out.put("open_positions_degraded", Boolean.TRUE.equals(body.get("open_positions_degraded")));
    out.put("open_positions_count", positions.size());
    out.put("sum_open_notional", notional);
    out.put("realized_pnl_today", body.get("realized_pnl_today"));
    out.put("realized_pnl_all_time", body.get("realized_pnl_all_time"));
    out.put("account_equity", equity);
    return out;
  }

  /**
   * {@code /api/trades} rows as demo fills. {@code subject} stays a JSON string (the dashboard's
   * fill renderer parses it) but carries only the named fill fields.
   */
  static List<Map<String, Object>> fills(
      List<Map<String, Object>> rows, Map<String, Instant> hiddenFrom) {
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
      m.put("entry_basis", r.get("entry_basis"));
      out.add(m);
    }
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
