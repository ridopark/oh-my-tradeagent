package com.ohmytradeagent.tdbff.preview;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The leak test for the public demo. Every source fixture carries the marker {@link #SECRET} in
 * EVERY field the demo must not expose (and in one unknown field, standing in for a column added
 * later). No projected output may contain the marker.
 */
class PreviewProjectionTest {

  private static final String SECRET = "SECRET";
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
  private static final Instant NOW = Instant.parse("2026-11-27T15:00:00Z");
  private static final OffsetDateTime AN_HOUR_AGO =
      OffsetDateTime.ofInstant(NOW.minus(Duration.ofHours(1)), ZoneOffset.UTC);
  private static final Instant THIRTY_DAYS_AGO = NOW.minus(Duration.ofDays(30));
  private static final String NVDA = "NVDA  261224C00200000";

  private static String json(Object o) throws Exception {
    return JSON.writeValueAsString(o);
  }

  private static Map<String, Object> position(String strategy, String contract) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("workflow_id", "t-staging_paper/s-x/pos/" + SECRET + "/chat-messages-" + SECRET);
    p.put("strategy_id", strategy);
    p.put("contract_symbol", contract);
    p.put("remaining_qty", 11L);
    p.put("entry_premium", new BigDecimal("3.36"));
    p.put("open_notional", new BigDecimal("3696.00"));
    p.put("entry_qty", 17L);
    p.put("realized_pl", new BigDecimal("55.00"));
    p.put("current_price", new BigDecimal("0.90"));
    p.put("unrealized_pl", new BigDecimal("-2706.00"));
    p.put("trailing_armed", false);
    p.put("some_future_field", SECRET);
    return p;
  }

  private static Map<String, Object> portfolioBody(List<Map<String, Object>> positions) {
    Map<String, Object> equity = new LinkedHashMap<>();
    equity.put("broker_target", "alpaca-paper");
    equity.put("equity", new BigDecimal("96591.36"));
    equity.put("last_equity", new BigDecimal("100390.36"));
    equity.put("today_pl", new BigDecimal("-3799.00"));
    equity.put("account_number", SECRET + "-PA3FKGPFYPLH");
    equity.put("options_buying_power", new BigDecimal("91806.36"));
    equity.put("options_approved_level", 3);
    Map<String, Object> unmanaged = new LinkedHashMap<>();
    unmanaged.put("occ", SECRET + "-condor-leg");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", SECRET + "-staging_paper");
    body.put("trading_day", "2026-11-27");
    body.put("open_positions", positions);
    body.put("open_positions_degraded", false);
    body.put("open_positions_count", positions.size());
    body.put("unmanaged_positions", List.of(unmanaged));
    body.put("sum_open_notional", new BigDecimal("999999"));
    body.put("realized_pnl_today", new BigDecimal("12.50"));
    body.put("realized_pnl_all_time", new BigDecimal("-431.25"));
    body.put("account_equity", List.of(equity));
    body.put("account_equity_scope", SECRET + " scope text");
    body.put("some_future_field", SECRET);
    return body;
  }

  private static Map<String, Object> fill(
      String kind, String strategy, String contract, OffsetDateTime at) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("event_id", SECRET + "-event");
    m.put("occurred_at", at);
    m.put("kind", kind);
    m.put("actor", "operator:tenant:staging_paper:" + SECRET + "@example.com");
    m.put("strategy_id", strategy);
    m.put("workflow_id", "t-staging_paper/s-x/sig/chat-messages-" + SECRET);
    m.put("correlation_id", "chat-messages-" + SECRET + ":0");
    m.put(
        "subject",
        "{\"signal_id\":\"chat-messages-"
            + SECRET
            + ":0\",\"intent_key\":\""
            + SECRET
            + ":entry\",\"operator_id\":\""
            + SECRET
            + "\",\"evidence\":\""
            + SECRET
            + " free text\",\"outcome\":\""
            + SECRET
            + "\",\"option_symbol\":\""
            + contract
            + "\",\"filled_qty\":17,\"avg_fill_price\":3.36,\"broker_order_id\":\"11111111-2222-4333-8444-555555555555\"}");
    m.put("entry_basis", new BigDecimal("3.36"));
    return m;
  }

  private static Map<String, Object> order(
      String strategy, String contract, String side, OffsetDateTime at, String lastError) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("intent_key", "t-staging_paper/s-x/sig/chat-messages-" + SECRET + ":entry");
    m.put("signal_id", "chat-messages-" + SECRET + ":0");
    m.put("strategy_id", strategy);
    m.put("broker_target", "alpaca-paper");
    m.put("option_symbol", contract);
    m.put("side", side);
    m.put("qty", 17L);
    m.put("limit_price", new BigDecimal("3.40"));
    m.put("state", lastError == null ? "FILLED" : "ERRORED");
    m.put("broker_order_id", "11111111-2222-4333-8444-555555555555");
    m.put("recorded_at", at);
    m.put("submitted_at", at);
    m.put("filled_qty", 17L);
    m.put("avg_fill_price", new BigDecimal("3.36"));
    m.put("filled_at", at);
    m.put("last_error", lastError);
    return m;
  }

  // ---- history (the equity chart) -------------------------------------------------------------

  private static final com.ohmytradeagent.tdbff.portfolio.PortfolioReturnCalculator CALC =
      new com.ohmytradeagent.tdbff.portfolio.PortfolioReturnCalculator();
  private static final Duration DELAY = Duration.ofMinutes(15);

  private static com.ohmytradeagent.contract.PortfolioHistoryResult historyResult(
      String timeframe, long[] ts, String[] equity) {
    com.ohmytradeagent.contract.PortfolioHistoryResult r =
        new com.ohmytradeagent.contract.PortfolioHistoryResult();
    List<Long> t = new ArrayList<>();
    List<BigDecimal> e = new ArrayList<>();
    List<BigDecimal> pl = new ArrayList<>();
    List<BigDecimal> pct = new ArrayList<>();
    for (int i = 0; i < ts.length; i++) {
      t.add(ts[i]);
      e.add(equity[i] == null ? null : new BigDecimal(equity[i]));
      pl.add(equity[i] == null ? null : new BigDecimal(equity[i]).subtract(new BigDecimal("1000")));
      pct.add(new BigDecimal("0.0123456789012345678"));
    }
    r.setTimestamps(t);
    r.setEquity(e);
    r.setProfitLoss(pl);
    r.setProfitLossPct(pct);
    r.setBaseValue(new BigDecimal("1000.004"));
    r.setBaseValueAsof(ts.length == 0 ? null : ts[0] - 60);
    r.setTimeframe(timeframe);
    r.setCashFlowsAvailable(true);
    r.setCashFlowTimestamps(List.of());
    r.setCashFlowAmounts(List.of());
    return r;
  }

  @Test
  @SuppressWarnings("unchecked")
  void history_intraday_keepsOnlyBarsThatEndedBeforeTheDelay() throws Exception {
    long now = NOW.getEpochSecond();
    // 5-minute bars. A bar ends 5 minutes after it starts, so the second ended exactly 15 minutes
    // ago (kept) and the third one second later than that (dropped), as are the two after it.
    long[] ts = {now - 2400, now - 1200, now - 1199, now - 900, now - 300};
    Map<String, Object> out =
        PreviewProjection.history(
            historyResult("5Min", ts, new String[] {"1010", "1020", "1030", "1040", "9999"}),
            NOW,
            DELAY,
            CALC);

    assertThat((List<Long>) out.get("timestamps")).containsExactly(now - 2400, now - 1200);
    assertThat(out.get("equity").toString()).isEqualTo("[1010.00, 1020.00]");
    assertThat(out.get("profit_loss").toString()).isEqualTo("[10.00, 20.00]");
    // The return for the range is what the KEPT bars show: 1020 against a 1000 base, not 9999.
    assertThat((BigDecimal) out.get("range_pl")).isEqualByComparingTo("20.00");
    assertThat(json(out)).doesNotContain("9999").doesNotContain("1030").doesNotContain("1040");
  }

  @Test
  @SuppressWarnings("unchecked")
  void history_daily_dropsTodaysBar_whichCarriesTheLiveBalance() throws Exception {
    long midnight = Instant.parse("2026-11-27T05:00:00Z").getEpochSecond(); // 00:00 New York
    long[] ts = {midnight - 2 * 86_400, midnight - 86_400, midnight};
    Map<String, Object> out =
        PreviewProjection.history(
            historyResult("1D", ts, new String[] {"1010", "1020", "9999"}), NOW, DELAY, CALC);

    // Today's bar started ten hours ago but is still in progress: its value is the balance NOW.
    assertThat((List<Long>) out.get("timestamps"))
        .containsExactly(midnight - 2 * 86_400, midnight - 86_400);
    assertThat(json(out)).doesNotContain("9999");
  }

  @Test
  void history_publishesNamedFieldsOnly_roundedToWhatAChartNeeds() throws Exception {
    long now = NOW.getEpochSecond();
    Map<String, Object> out =
        PreviewProjection.history(
            historyResult("5Min", new long[] {now - 3000}, new String[] {"1010.123456789"}),
            NOW,
            DELAY,
            CALC);

    assertThat(out)
        .containsOnlyKeys(
            "timestamps",
            "equity",
            "profit_loss",
            "profit_loss_pct",
            "base_value",
            "base_value_asof",
            "timeframe",
            "range_pl",
            "range_pl_pct",
            "cash_flows_available");
    assertThat(out.get("equity").toString()).isEqualTo("[1010.12]");
    assertThat(out.get("profit_loss_pct").toString()).isEqualTo("[0.012346]");
    assertThat((BigDecimal) out.get("base_value")).isEqualByComparingTo("1000.00");
    // No run of digits long enough to be mistaken for (or to carry) an id.
    assertThat(json(out)).doesNotContainPattern("\\d{12,}");
  }

  @Test
  void history_skipsABarWithNoEquity_andPublishesNothingForAnUnknownTimeframeOrNoBars() {
    long now = NOW.getEpochSecond();
    Map<String, Object> sparse =
        PreviewProjection.history(
            historyResult("5Min", new long[] {now - 3000, now - 2700}, new String[] {null, "1020"}),
            NOW,
            DELAY,
            CALC);
    assertThat(sparse.get("equity").toString()).isEqualTo("[1020.00]");

    assertThat(
            PreviewProjection.history(
                historyResult("1H", new long[] {now - 90_000}, new String[] {"1"}),
                NOW,
                DELAY,
                CALC))
        .isNull();
    assertThat(
            PreviewProjection.history(
                historyResult("5Min", new long[] {now - 60}, new String[] {"1"}), NOW, DELAY, CALC))
        .isNull();
    assertThat(PreviewProjection.history(null, NOW, DELAY, CALC)).isNull();
  }

  // ---- portfolio ----------------------------------------------------------------------------

  @Test
  void portfolio_carriesNoHiddenValue_andNoUnknownField() throws Exception {
    Map<String, Object> out =
        PreviewProjection.portfolio(
            portfolioBody(List.of(position("copytrade-v1", NVDA))),
            Map.of(),
            portfolioBody(List.of()));

    String body = json(out);
    assertThat(body).doesNotContain(SECRET);
    assertThat(out)
        .containsOnlyKeys(
            "trading_day",
            "open_positions",
            "open_positions_degraded",
            "open_positions_count",
            "sum_open_notional",
            "realized_pnl_today",
            "realized_pnl_all_time",
            "account_equity");
    JsonNode equity = JSON.readTree(body).get("account_equity").get(0);
    assertThat(equity.fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder("broker_target", "equity", "last_equity", "today_pl");
    JsonNode row = JSON.readTree(body).get("open_positions").get(0);
    assertThat(row.has("workflow_id")).isFalse();
    // Summed per entry signal id upstream, which watchlist legs share: never published.
    assertThat(row.has("entry_qty")).isFalse();
    assertThat(row.has("realized_pl")).isFalse();
  }

  @Test
  void portfolio_takesAccountTotalsFromTheDelayedBody_notTheLiveOne() {
    Map<String, Object> live = portfolioBody(List.of(position("copytrade-v1", NVDA)));
    live.put("realized_pnl_today", new BigDecimal("999.00"));
    Map<String, Object> delayed = portfolioBody(List.of());

    Map<String, Object> out = PreviewProjection.portfolio(live, Map.of(), delayed);

    assertThat((BigDecimal) out.get("realized_pnl_today")).isEqualByComparingTo("12.50");
    assertThat((BigDecimal) out.get("realized_pnl_all_time")).isEqualByComparingTo("-431.25");
    assertThat((List<?>) out.get("account_equity")).hasSize(1);
    // Positions still come from the live body.
    assertThat(out.get("open_positions_count")).isEqualTo(1);
  }

  @Test
  void portfolio_withNoDelayedBody_publishesNoAccountTotals() {
    Map<String, Object> out =
        PreviewProjection.portfolio(
            portfolioBody(List.of(position("copytrade-v1", NVDA))), Map.of(), null);

    assertThat(out.get("realized_pnl_today")).isNull();
    assertThat(out.get("realized_pnl_all_time")).isNull();
    assertThat((List<?>) out.get("account_equity")).isEmpty();
    assertThat(out).containsKeys("realized_pnl_today", "realized_pnl_all_time", "account_equity");
    assertThat(out.get("open_positions_count")).isEqualTo(1);
  }

  @Test
  void portfolio_dropsOtherStrategiesAndQuarantinedContracts_andRecomputesTotals() {
    List<Map<String, Object>> positions = new ArrayList<>();
    positions.add(position("copytrade-v1", NVDA));
    positions.add(position("gated_condor", "SPY   261127C00600000"));
    positions.add(position("copytrade-lotto-v1", "GOOGL 261204C00285000"));

    Map<String, Object> out =
        PreviewProjection.portfolio(
            portfolioBody(positions),
            Map.of("GOOGL261204C00285000", NOW.minus(Duration.ofMinutes(3))),
            portfolioBody(positions));

    assertThat(out.get("open_positions_count")).isEqualTo(1);
    assertThat((BigDecimal) out.get("sum_open_notional")).isEqualByComparingTo("3696.00");
    assertThat(out.get("open_positions").toString()).contains("NVDA").doesNotContain("GOOGL");
  }

  // ---- fills --------------------------------------------------------------------------------

  @Test
  void fills_carryNoHiddenValue_andKeepTheFieldsTheRendererReads() throws Exception {
    List<Map<String, Object>> out =
        PreviewProjection.fills(
            List.of(fill("EntryFilled", "copytrade-v1", NVDA, AN_HOUR_AGO)), Map.of(), Map.of());

    assertThat(json(out)).doesNotContain(SECRET);
    assertThat(out).hasSize(1);
    assertThat(out.get(0))
        .containsOnlyKeys("occurred_at", "kind", "strategy_id", "subject", "entry_basis");
    JsonNode subject = JSON.readTree((String) out.get(0).get("subject"));
    assertThat(subject.fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder(
            "option_symbol", "filled_qty", "avg_fill_price", "broker_order_id");
    assertThat(subject.get("filled_qty").asInt()).isEqualTo(17);
  }

  @Test
  void fills_dropOtherStrategies_quarantinedRows_andUnreadableSubjects() {
    OffsetDateTime twoMinAgo =
        OffsetDateTime.ofInstant(NOW.minus(Duration.ofMinutes(2)), ZoneOffset.UTC);
    Map<String, Object> garbled = fill("EntryFilled", "copytrade-v1", NVDA, AN_HOUR_AGO);
    garbled.put("subject", "not json " + SECRET);
    Map<String, Object> noSymbol = fill("EntryFilled", "copytrade-v1", NVDA, AN_HOUR_AGO);
    noSymbol.put("subject", "{\"filled_qty\":1}");

    List<Map<String, Object>> out =
        PreviewProjection.fills(
            List.of(
                fill("EntryFilled", "copytrade-v1", NVDA, twoMinAgo),
                fill("EntryFilled", "copytrade-v1", NVDA, AN_HOUR_AGO),
                fill("EntryFilled", "gated_condor", NVDA, AN_HOUR_AGO),
                garbled,
                noSymbol),
            Map.of("NVDA261224C00200000", NOW.minus(Duration.ofMinutes(3))),
            Map.of());

    // Only the hour-old copytrade fill: it predates the quarantine start.
    assertThat(out).hasSize(1);
    assertThat(out.get(0).get("occurred_at")).isEqualTo(AN_HOUR_AGO);
  }

  @Test
  void fills_nestedValueUnderAnAllowedName_isDropped() throws Exception {
    Map<String, Object> f = fill("EntryFilled", "copytrade-v1", NVDA, AN_HOUR_AGO);
    f.put(
        "subject",
        "{\"option_symbol\":\"" + NVDA + "\",\"broker_order_id\":{\"x\":\"" + SECRET + "\"}}");

    assertThat(json(PreviewProjection.fills(List.of(f), Map.of(), Map.of())))
        .doesNotContain(SECRET);
  }

  // ---- orders -------------------------------------------------------------------------------

  @Test
  void orders_carryNoHiddenValue_andErrorTextBecomesAFlag() throws Exception {
    List<Map<String, Object>> out =
        PreviewProjection.orders(
            List.of(
                order("copytrade-v1", NVDA, "BUY", AN_HOUR_AGO, null),
                order("copytrade-v1", NVDA, "SELL", AN_HOUR_AGO, SECRET + " broker said no")),
            Map.of(),
            THIRTY_DAYS_AGO);

    assertThat(json(out)).doesNotContain(SECRET);
    assertThat(out).hasSize(2);
    assertThat(out.get(0)).doesNotContainKeys("intent_key", "signal_id", "last_error");
    assertThat(out.get(0).get("failed")).isEqualTo(false);
    assertThat(out.get(1).get("failed")).isEqualTo(true);
    assertThat(out.get(0).get("broker_order_id")).isEqualTo("11111111-2222-4333-8444-555555555555");
  }

  @Test
  void orders_dropOtherStrategies_quarantinedRows_andRowsOlderThanTheWindow() {
    OffsetDateTime twoMinAgo =
        OffsetDateTime.ofInstant(NOW.minus(Duration.ofMinutes(2)), ZoneOffset.UTC);
    OffsetDateTime fortyDaysAgo =
        OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(40)), ZoneOffset.UTC);
    Map<String, Object> noTime = order("copytrade-v1", NVDA, "BUY", AN_HOUR_AGO, null);
    noTime.put("recorded_at", null);

    List<Map<String, Object>> out =
        PreviewProjection.orders(
            List.of(
                order("copytrade-v1", NVDA, "BUY", twoMinAgo, null),
                order("copytrade-v1", NVDA, "SELL", AN_HOUR_AGO, null),
                order("gated_condor", "MLEG", "SELL", AN_HOUR_AGO, null),
                order("copytrade-v1", NVDA, "BUY", fortyDaysAgo, null),
                noTime),
            Map.of("NVDA261224C00200000", NOW.minus(Duration.ofMinutes(3))),
            THIRTY_DAYS_AGO);

    assertThat(out).hasSize(1);
    assertThat(out.get(0).get("side")).isEqualTo("SELL");
  }

  @Test
  void brokerOrderId_thatIsNotAUuid_isNotPublished() throws Exception {
    Map<String, Object> o = order("copytrade-v1", NVDA, "BUY", AN_HOUR_AGO, null);
    o.put("broker_order_id", "adopt:" + SECRET);
    Map<String, Object> f = fill("EntryFilled", "copytrade-v1", NVDA, AN_HOUR_AGO);
    f.put(
        "subject",
        "{\"option_symbol\":\"" + NVDA + "\",\"broker_order_id\":\"adopt:" + SECRET + "\"}");

    List<Map<String, Object>> orders =
        PreviewProjection.orders(List.of(o), Map.of(), THIRTY_DAYS_AGO);
    List<Map<String, Object>> fills = PreviewProjection.fills(List.of(f), Map.of(), Map.of());

    assertThat(json(orders)).doesNotContain(SECRET);
    assertThat(orders.get(0)).doesNotContainKey("broker_order_id");
    assertThat(json(fills)).doesNotContain(SECRET).doesNotContain("broker_order_id");
  }
}
