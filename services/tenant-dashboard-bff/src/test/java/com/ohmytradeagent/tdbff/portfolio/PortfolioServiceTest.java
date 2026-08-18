package com.ohmytradeagent.tdbff.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.positions.PositionsReader;
import com.ohmytradeagent.tdbff.positions.PositionsReader.OpenPosition;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PortfolioServiceTest {

  private final PositionsReader positionsReader = mock(PositionsReader.class);
  private final RealizedPnlCalculator realizedPnl = mock(RealizedPnlCalculator.class);
  private final AccountEquityClient accountEquity = mock(AccountEquityClient.class);
  private final BrokerPositionsClient brokerPositions = mock(BrokerPositionsClient.class);
  private final TenantStrategyResolver strategyResolver = mock(TenantStrategyResolver.class);
  private final DbStrategyConfigReader strategyRegistry = mock(DbStrategyConfigReader.class);

  private final PortfolioService service = newService(false, 9);

  PortfolioServiceTest() {
    // Default: no live marks (fail-open empty). Tests that exercise the join override this.
    when(brokerPositions.marksFor(any(), any(), any())).thenReturn(Map.of());
    // Default realized P&L (today + all-time) to zero from the SINGLE consolidated call; the
    // aggregation test overrides per strategy.
    when(realizedPnl.computeRealized(any(), any(), any())).thenReturn(rp("0", "0"));
  }

  // Builds the consolidated {today, all-time} record PortfolioService now reads per strategy.
  private static RealizedPnlCalculator.RealizedPnl rp(String today, String allTime) {
    return new RealizedPnlCalculator.RealizedPnl(new BigDecimal(today), new BigDecimal(allTime));
  }

  private PortfolioService newService(boolean exposeAccountNumber, long subreadTimeoutSeconds) {
    return new PortfolioService(
        positionsReader,
        realizedPnl,
        accountEquity,
        brokerPositions,
        strategyResolver,
        strategyRegistry,
        exposeAccountNumber,
        subreadTimeoutSeconds);
  }

  @Test
  @SuppressWarnings("unchecked")
  void sumsRealizedPnlAcrossStrategiesAndUnionsEquityPerBrokerTarget() {
    // Two strategies; realized P&L must be summed and the two equal broker_targets unioned to one.
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1", "s2"));
    when(positionsReader.openPositions("acme"))
        .thenReturn(
            List.of(
                new OpenPosition(
                    "wf1", "s1", "SYM1", 2, new BigDecimal("1.50"), new BigDecimal("300.00")),
                new OpenPosition(
                    "wf2", "s2", "SYM2", 1, new BigDecimal("2.00"), new BigDecimal("200.00"))));
    // One consolidated call per strategy carries BOTH today and all-time.
    when(realizedPnl.computeRealized(eq("acme"), eq("s1"), any(LocalDate.class)))
        .thenReturn(rp("100.00", "250.00"));
    when(realizedPnl.computeRealized(eq("acme"), eq("s2"), any(LocalDate.class)))
        .thenReturn(rp("50.00", "-30.00"));
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-paper");
    when(strategyRegistry.brokerTarget("acme", "s2")).thenReturn("alpaca-paper"); // same -> union
    when(accountEquity.snapshotFor("acme", "alpaca-paper"))
        .thenReturn(
            new AccountEquityClient.BrokerAccount(new BigDecimal("10000.00"), "PA3ER05HLHMB"));

    Map<String, Object> body = service.portfolio("acme");

    assertThat(body.get("tenant_id")).isEqualTo("acme");
    // P&L summed across strategies: 100 + 50.
    assertThat(body.get("realized_pnl_today")).isEqualTo(new BigDecimal("150.00"));
    // Since-inception P&L summed across strategies the same way: 250 + (-30).
    assertThat(body.get("realized_pnl_all_time")).isEqualTo(new BigDecimal("220.00"));
    // Open notional summed across positions: 300 + 200.
    assertThat(body.get("sum_open_notional")).isEqualTo(new BigDecimal("500.00"));
    assertThat(body.get("open_positions_count")).isEqualTo(2);
    // Both strategies route to the same broker_target -> a single equity entry.
    var equity = (List<Map<String, Object>>) body.get("account_equity");
    assertThat(equity).hasSize(1);
    assertThat(equity.get(0)).containsEntry("broker_target", "alpaca-paper");
    assertThat(equity.get(0)).containsEntry("equity", new BigDecimal("10000.00"));
    // Flag defaults false -> the informational account_number is NOT exposed even when present.
    assertThat(equity.get(0)).doesNotContainKey("account_number");
    // Honest-labeling fields are always present.
    assertThat(body).containsEntry("unrealized_pnl", null);
    assertThat(body.get("account_equity_scope")).asString().contains("NOT this tenant's");

    // ONE full-history fetch per strategy: the consolidated call runs once each and the old
    // two-pass methods are never invoked from PortfolioService.
    verify(realizedPnl).computeRealized(eq("acme"), eq("s1"), any(LocalDate.class));
    verify(realizedPnl).computeRealized(eq("acme"), eq("s2"), any(LocalDate.class));
    verify(realizedPnl, never()).computeRealizedPnl(any(), any(), any());
    verify(realizedPnl, never()).computeRealizedPnlAllTime(any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void emitsIntradayTodayPlFromEquityMinusLastEquity_prodRealLossCase() {
    // The live-incident shape: equity=50,477.06, last_equity=52,259.56 (prior market close) →
    // today_pl = equity - last_equity = -1,782.50 (a LOSS). This is the GENUINE intraday "today",
    // not Alpaca portfolio-history's last completed daily bar. last_equity is surfaced alongside so
    // the header can aggregate the percentage denominator across broker_targets.
    when(strategyResolver.strategyIdsForTenant("prod_real")).thenReturn(List.of("s1"));
    when(positionsReader.openPositions("prod_real")).thenReturn(List.of());
    when(realizedPnl.computeRealized(eq("prod_real"), any(), any(LocalDate.class)))
        .thenReturn(rp("0", "0"));
    when(strategyRegistry.brokerTarget("prod_real", "s1")).thenReturn("alpaca-live");
    when(accountEquity.snapshotFor("prod_real", "alpaca-live"))
        .thenReturn(
            new AccountEquityClient.BrokerAccount(
                new BigDecimal("50477.06"), "847309116", new BigDecimal("52259.56")));

    var equity = (List<Map<String, Object>>) service.portfolio("prod_real").get("account_equity");

    assertThat(equity).hasSize(1);
    assertThat((BigDecimal) equity.get(0).get("today_pl")).isEqualByComparingTo("-1782.50");
    assertThat((BigDecimal) equity.get(0).get("last_equity")).isEqualByComparingTo("52259.56");
  }

  @Test
  @SuppressWarnings("unchecked")
  void todayPlIsNullWhenLastEquityUnavailable() {
    // last_equity absent (older producer / broker adapter that doesn't expose it, or a degraded
    // snapshot) → today_pl and today_pl_pct are null (never fabricated); the dashboard falls back
    // to
    // the last completed daily bar.
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(positionsReader.openPositions("acme")).thenReturn(List.of());
    when(realizedPnl.computeRealized(eq("acme"), any(), any(LocalDate.class)))
        .thenReturn(rp("0", "0"));
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-paper");
    // Two-arg BrokerAccount => lastEquity is null.
    when(accountEquity.snapshotFor("acme", "alpaca-paper"))
        .thenReturn(new AccountEquityClient.BrokerAccount(new BigDecimal("10000.00"), null));

    var equity = (List<Map<String, Object>>) service.portfolio("acme").get("account_equity");

    assertThat(equity).hasSize(1);
    assertThat(equity.get(0)).containsEntry("today_pl", null);
    assertThat(equity.get(0)).containsEntry("last_equity", null);
  }

  @Test
  @SuppressWarnings("unchecked")
  void distinctBrokerTargetsEachGetTheirOwnEquityEntry() {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1", "s2"));
    when(positionsReader.openPositions("acme")).thenReturn(List.of());
    when(realizedPnl.computeRealized(eq("acme"), any(), any(LocalDate.class)))
        .thenReturn(rp("0", "0"));
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-paper");
    when(strategyRegistry.brokerTarget("acme", "s2")).thenReturn("tradier-paper");
    when(accountEquity.snapshotFor("acme", "alpaca-paper"))
        .thenReturn(new AccountEquityClient.BrokerAccount(new BigDecimal("100"), null));
    when(accountEquity.snapshotFor("acme", "tradier-paper"))
        .thenReturn(new AccountEquityClient.BrokerAccount(new BigDecimal("200"), null));

    Map<String, Object> body = service.portfolio("acme");

    var equity = (List<Map<String, Object>>) body.get("account_equity");
    assertThat(equity).hasSize(2);
    assertThat(equity)
        .extracting(m -> m.get("broker_target"))
        .containsExactly("alpaca-paper", "tradier-paper");
  }

  @Test
  @SuppressWarnings("unchecked")
  void exposesAccountNumberOnlyWhenTheDevFlagIsEnabled() {
    // Same fixture, two flag states: FALSE omits account_number; TRUE includes it.
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(positionsReader.openPositions("acme")).thenReturn(List.of());
    when(realizedPnl.computeRealized(eq("acme"), any(), any(LocalDate.class)))
        .thenReturn(rp("0", "0"));
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-paper");
    when(accountEquity.snapshotFor("acme", "alpaca-paper"))
        .thenReturn(
            new AccountEquityClient.BrokerAccount(new BigDecimal("10000.00"), "PA3ER05HLHMB"));

    PortfolioService flagOff = newService(false, 9);
    var offRows = (List<Map<String, Object>>) flagOff.portfolio("acme").get("account_equity");
    assertThat(offRows).hasSize(1);
    assertThat(offRows.get(0)).containsEntry("equity", new BigDecimal("10000.00"));
    assertThat(offRows.get(0)).doesNotContainKey("account_number");

    PortfolioService flagOn = newService(true, 9);
    var onRows = (List<Map<String, Object>>) flagOn.portfolio("acme").get("account_equity");
    assertThat(onRows).hasSize(1);
    assertThat(onRows.get(0)).containsEntry("account_number", "PA3ER05HLHMB");
  }

  @Test
  @SuppressWarnings("unchecked")
  void positionSubReadTimeoutDegradesToEmptyNotWholePageFailure() throws Exception {
    // A stalled position-state fan-out (e.g. orchestrator worker down) must NOT fail the page: the
    // positions section degrades to empty while the (fast) equity section still comes through.
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(positionsReader.openPositions("acme"))
        .thenAnswer(
            inv -> {
              Thread.sleep(3000);
              return List.of();
            });
    when(realizedPnl.computeRealized(eq("acme"), any(), any(LocalDate.class)))
        .thenReturn(rp("0", "0"));
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-paper");
    when(accountEquity.snapshotFor("acme", "alpaca-paper"))
        .thenReturn(new AccountEquityClient.BrokerAccount(new BigDecimal("100"), null));

    // 1s budget so the stalled sub-read degrades quickly under test.
    PortfolioService fast = newService(false, 1);
    Map<String, Object> body = fast.portfolio("acme");

    assertThat(body.get("open_positions_count")).isEqualTo(0);
    assertThat((List<Map<String, Object>>) body.get("open_positions")).isEmpty();
    assertThat(body.get("sum_open_notional")).isEqualTo(BigDecimal.ZERO);
    // Equity sub-read was fast -> unaffected.
    var equity = (List<Map<String, Object>>) body.get("account_equity");
    assertThat(equity).hasSize(1);
    assertThat(equity.get(0)).containsEntry("equity", new BigDecimal("100"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void equitySnapshotTimeoutDegradesToNullEquityNotWholePageFailure() throws Exception {
    // A stalled AccountSnapshotWorkflow must degrade to null equity for that broker only — the row
    // is still present and the positions section is unaffected.
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(positionsReader.openPositions("acme"))
        .thenReturn(
            List.of(
                new OpenPosition(
                    "wf1", "s1", "SYM1", 1, new BigDecimal("2.00"), new BigDecimal("200.00"))));
    when(realizedPnl.computeRealized(eq("acme"), any(), any(LocalDate.class)))
        .thenReturn(rp("0", "0"));
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-paper");
    when(accountEquity.snapshotFor("acme", "alpaca-paper"))
        .thenAnswer(
            inv -> {
              Thread.sleep(3000);
              return new AccountEquityClient.BrokerAccount(new BigDecimal("999"), null);
            });

    PortfolioService fast = newService(false, 1);
    Map<String, Object> body = fast.portfolio("acme");

    var equity = (List<Map<String, Object>>) body.get("account_equity");
    assertThat(equity).hasSize(1);
    assertThat(equity.get(0)).containsEntry("broker_target", "alpaca-paper");
    assertThat(equity.get(0)).containsEntry("equity", null);
    // Positions sub-read was fast -> unaffected.
    assertThat(body.get("open_positions_count")).isEqualTo(1);
  }

  @Test
  void realizedScanTimeoutDegradesBothTodayAndAllTimeToNull() throws Exception {
    // The realized calc is now a SINGLE full-history scan per strategy that yields today AND
    // all-time. A stalled scan degrades that ONE contribution to null, so BOTH aggregates publish
    // as NULL (each tile renders "—", unavailable) — NOT BigDecimal.ZERO, which would show a
    // misleading $0.00 and silently under-count.
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(positionsReader.openPositions("acme")).thenReturn(List.of());
    when(realizedPnl.computeRealized(eq("acme"), any(), any(LocalDate.class)))
        .thenAnswer(
            inv -> {
              Thread.sleep(3000);
              return rp("777.00", "999.00");
            });
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-paper");
    when(accountEquity.snapshotFor("acme", "alpaca-paper"))
        .thenReturn(new AccountEquityClient.BrokerAccount(new BigDecimal("100"), null));

    PortfolioService fast = newService(false, 1);
    Map<String, Object> body = fast.portfolio("acme");

    // A single degraded strategy null-seeds BOTH aggregates (renders "—"), not a misleading 0.
    assertThat(body).containsEntry("realized_pnl_today", null);
    assertThat(body).containsEntry("realized_pnl_all_time", null);
  }

  @Test
  void crossDayTodayFigureIsFifoLoss_notPhantomProceeds() {
    // prod_real 2026-07-22: a prior-day AAPL call sold today at a loss. The fixed day-scoped calc
    // returns the FIFO loss (-121), NOT the raw-proceeds phantom (+2068). This test locks that the
    // (now concurrent) daily path publishes whatever the calc returns — the FIFO correctness itself
    // is covered in RealizedPnlCalculatorUnitTest / IT.
    when(strategyResolver.strategyIdsForTenant("prod_real")).thenReturn(List.of("copytrade-v1"));
    when(positionsReader.openPositions("prod_real")).thenReturn(List.of());
    when(realizedPnl.computeRealized(eq("prod_real"), eq("copytrade-v1"), any(LocalDate.class)))
        .thenReturn(rp("-121.00", "-121.00")); // today = FIFO loss, not the +2068 phantom
    when(strategyRegistry.brokerTarget("prod_real", "copytrade-v1")).thenReturn("alpaca-live");
    when(accountEquity.snapshotFor("prod_real", "alpaca-live"))
        .thenReturn(new AccountEquityClient.BrokerAccount(new BigDecimal("50000.00"), null));

    Map<String, Object> body = service.portfolio("prod_real");

    assertThat(body.get("realized_pnl_today")).isEqualTo(new BigDecimal("-121.00"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void joinsLiveMarksByNormalizedCompactOcc_padInsensitively() {
    // The tracked position carries the PADDED canonical OCC; the broker marks are keyed COMPACT.
    // The join must normalize both to compact so a padded-vs-compact form still matches.
    String paddedOcc = "SPY   260519C00737000";
    String compactOcc = "SPY260519C00737000";
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(positionsReader.openPositions("acme"))
        .thenReturn(
            List.of(
                new OpenPosition(
                    "wf1", "s1", paddedOcc, 5, new BigDecimal("0.84"), new BigDecimal("420.00"))));
    when(realizedPnl.computeRealized(eq("acme"), any(), any(LocalDate.class)))
        .thenReturn(rp("0", "0"));
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-paper");
    when(accountEquity.snapshotFor("acme", "alpaca-paper"))
        .thenReturn(new AccountEquityClient.BrokerAccount(new BigDecimal("10000"), null));
    when(brokerPositions.marksFor("alpaca-paper", "acme", "s1"))
        .thenReturn(
            Map.of(
                compactOcc,
                new BrokerPositionsClient.PositionMarks(
                    new BigDecimal("1.20"), new BigDecimal("180.00"), new BigDecimal("-15.00"))));

    Map<String, Object> body = service.portfolio("acme");

    var positions = (List<Map<String, Object>>) body.get("open_positions");
    assertThat(positions).hasSize(1);
    Map<String, Object> pos = positions.get(0);
    // Existing fields preserved.
    assertThat(pos).containsEntry("contract_symbol", paddedOcc);
    assertThat(pos).containsEntry("remaining_qty", 5L);
    assertThat(pos).containsEntry("entry_premium", new BigDecimal("0.84"));
    // Live marks joined: current price + today's + total unrealized P&L (today is signed negative).
    assertThat((BigDecimal) pos.get("current_price")).isEqualByComparingTo("1.20");
    assertThat((BigDecimal) pos.get("unrealized_pl")).isEqualByComparingTo("180.00");
    assertThat((BigDecimal) pos.get("unrealized_intraday_pl")).isEqualByComparingTo("-15.00");
  }

  @Test
  @SuppressWarnings("unchecked")
  void positionWithoutMatchingBrokerMark_staysCleanNoMarkFields() {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(positionsReader.openPositions("acme"))
        .thenReturn(
            List.of(
                new OpenPosition(
                    "wf1",
                    "s1",
                    "AAPL260116C00200000",
                    1,
                    new BigDecimal("2.00"),
                    new BigDecimal("200.00"))));
    when(realizedPnl.computeRealized(eq("acme"), any(), any(LocalDate.class)))
        .thenReturn(rp("0", "0"));
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-paper");
    when(accountEquity.snapshotFor("acme", "alpaca-paper"))
        .thenReturn(new AccountEquityClient.BrokerAccount(new BigDecimal("10000"), null));
    // Broker holds a DIFFERENT contract — no mark for this position's OCC.
    when(brokerPositions.marksFor("alpaca-paper", "acme", "s1"))
        .thenReturn(
            Map.of(
                "NVDA260516C00140000",
                new BrokerPositionsClient.PositionMarks(
                    new BigDecimal("3.00"), new BigDecimal("90.00"), new BigDecimal("10.00"))));

    Map<String, Object> body = service.portfolio("acme");

    var positions = (List<Map<String, Object>>) body.get("open_positions");
    assertThat(positions).hasSize(1);
    Map<String, Object> pos = positions.get(0);
    // No mark for this OCC -> the row renders with only its base fields, no mark keys.
    assertThat(pos).doesNotContainKey("current_price");
    assertThat(pos).doesNotContainKey("unrealized_pl");
    assertThat(pos).doesNotContainKey("unrealized_intraday_pl");
    assertThat(pos).containsEntry("contract_symbol", "AAPL260116C00200000");
  }

  @Test
  @SuppressWarnings("unchecked")
  void armedPositionPublishesItsTrailingStopSoTheRowCanShowIt() {
    // /live's per-position stop badge reads these three keys. Without them an armed position
    // rendered identically to an unprotected one after a refresh.
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(positionsReader.openPositions("acme"))
        .thenReturn(
            List.of(
                new OpenPosition(
                    "wf1",
                    "s1",
                    "DRAM  270319C00100000",
                    2,
                    new BigDecimal("3.28"),
                    new BigDecimal("656.00"),
                    true,
                    new BigDecimal("0.35"),
                    new BigDecimal("2.63"))));
    when(realizedPnl.computeRealized(eq("acme"), any(), any(LocalDate.class)))
        .thenReturn(rp("0", "0"));
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-paper");
    when(accountEquity.snapshotFor("acme", "alpaca-paper"))
        .thenReturn(new AccountEquityClient.BrokerAccount(new BigDecimal("10000"), null));
    when(brokerPositions.marksFor("alpaca-paper", "acme", "s1")).thenReturn(Map.of());

    Map<String, Object> body = service.portfolio("acme");

    var positions = (List<Map<String, Object>>) body.get("open_positions");
    Map<String, Object> pos = positions.get(0);
    assertThat(pos).containsEntry("trailing_armed", true);
    assertThat((BigDecimal) pos.get("trail_giveback_pct")).isEqualByComparingTo("0.35");
    // Peak-anchored and published verbatim — NOT re-derivable from any other key on this row.
    assertThat((BigDecimal) pos.get("trail_stop_price")).isEqualByComparingTo("2.63");
  }

  @Test
  @SuppressWarnings("unchecked")
  void unarmedPositionStatesItIsUnarmedAndOmitsTheTrailNumbers() {
    when(strategyResolver.strategyIdsForTenant("acme")).thenReturn(List.of("s1"));
    when(positionsReader.openPositions("acme"))
        .thenReturn(
            List.of(
                new OpenPosition(
                    "wf1",
                    "s1",
                    "AAPL260116C00200000",
                    1,
                    new BigDecimal("2.00"),
                    new BigDecimal("200.00"))));
    when(realizedPnl.computeRealized(eq("acme"), any(), any(LocalDate.class)))
        .thenReturn(rp("0", "0"));
    when(strategyRegistry.brokerTarget("acme", "s1")).thenReturn("alpaca-paper");
    when(accountEquity.snapshotFor("acme", "alpaca-paper"))
        .thenReturn(new AccountEquityClient.BrokerAccount(new BigDecimal("10000"), null));
    when(brokerPositions.marksFor("alpaca-paper", "acme", "s1")).thenReturn(Map.of());

    Map<String, Object> body = service.portfolio("acme");

    var positions = (List<Map<String, Object>>) body.get("open_positions");
    Map<String, Object> pos = positions.get(0);
    // Stated explicitly rather than by omission: a missing key and "not protected" must not be the
    // same signal on a row about a live position.
    assertThat(pos).containsEntry("trailing_armed", false);
    assertThat(pos).doesNotContainKey("trail_giveback_pct");
    assertThat(pos).doesNotContainKey("trail_stop_price");
  }
}
