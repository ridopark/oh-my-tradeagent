package com.ohmytradeagent.tdbff.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.tdbff.config.BrokerDataSourceRouter;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DB-backed coverage of the realized-PnL calc against the exec broker's {@code
 * order_intent_journal} (the new broker-truth source). Exercises the real Postgres {@code filled_at
 * AT TIME ZONE 'America/New_York'} date predicate and the FILLED-only / side split. Gated on {@code
 * RUN_DB_ITS=true} like the other DB-backed ITs. The journal DDL is inlined (the BFF does not own
 * that schema) — only the columns the calculator reads are created. The calculator is constructed
 * with a {@link BrokerDataSourceRouter} stub returning this container's DSLContext and a {@link
 * DbStrategyConfigReader} stub returning a fixed broker_target.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class RealizedPnlCalculatorIT {

  private static final String TENANT = "prod_real";
  private static final String STRATEGY = "copytrade-v1";
  private static final String BROKER_TARGET = "alpaca-live";

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static java.sql.Connection conn;
  private static DSLContext dsl;
  private RealizedPnlCalculator svc;

  @BeforeAll
  static void initDb() throws Exception {
    conn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dsl = DSL.using(conn, SQLDialect.POSTGRES);
    dsl.execute(
        "CREATE TABLE order_intent_journal ("
            + "  id BIGSERIAL PRIMARY KEY,"
            + "  tenant_id VARCHAR(64) NOT NULL,"
            + "  strategy_id VARCHAR(64) NOT NULL,"
            + "  intent_key UUID NOT NULL UNIQUE,"
            + "  option_symbol VARCHAR(64),"
            + "  side VARCHAR(8) NOT NULL,"
            + "  qty INTEGER,"
            + "  state VARCHAR(16) NOT NULL,"
            + "  filled_qty INTEGER,"
            + "  avg_fill_price NUMERIC,"
            + "  filled_at TIMESTAMPTZ,"
            + "  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now())");
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (conn != null) conn.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute("DELETE FROM order_intent_journal");
    BrokerDataSourceRouter router = mock(BrokerDataSourceRouter.class);
    when(router.dslFor(BROKER_TARGET)).thenReturn(dsl);
    DbStrategyConfigReader registry = mock(DbStrategyConfigReader.class);
    when(registry.brokerTarget(TENANT, STRATEGY)).thenReturn(BROKER_TARGET);
    svc = new RealizedPnlCalculator(router, registry);
  }

  @Test
  void dramLiveLoss_excludesNonFilledRows_allTimeAndDayScoped() {
    // Live prod_real 2026-06-29 DRAM: bought 3 @ 2.3533 FILLED, sold 2 @ 1.84 FILLED on 6/29.
    String dram = "DRAM  260717C00030000";
    insert("BUY", dram, 3, "FILLED", 3, "2.3533", "2026-06-26T14:00:00Z");
    insert("SELL", dram, 2, "FILLED", 2, "1.84", "2026-06-29T18:00:00Z");
    // Noise that must NOT count: a CANCELLED sell (no fill), an ERRORED buy/sell, a RECORDED sell.
    insert("SELL", dram, 2, "CANCELLED", null, null, null);
    insert("BUY", dram, 50, "ERRORED", null, null, null);
    insert("SELL", dram, 2, "ERRORED", null, null, null);
    insert("SELL", dram, 1, "RECORDED", null, null, null);

    // All-time: the 2 sold FIFO-match the 2.3533 basis -> 2 * (1.84 - 2.3533) * 100 = -102.66.
    assertThat(svc.computeRealizedPnlAllTime(TENANT, STRATEGY)).isEqualByComparingTo("-102.66");

    // Day-scoped on the exit day (6/29 America/New_York = 18:00Z). The buy was a PRIOR day, but the
    // day-scoped calc now fetches full history and FIFO-matches the cross-day exit against the real
    // 2.3533 basis (#276 §4 phantom fix): 2 * (1.84 - 2.3533) * 100 = -102.66 — attributed to 6/29.
    assertThat(svc.computeRealizedPnl(TENANT, STRATEGY, LocalDate.of(2026, 6, 29)))
        .isEqualByComparingTo("-102.66");

    // Day-scoped on an unrelated day -> no fills in scope -> 0.
    assertThat(svc.computeRealizedPnl(TENANT, STRATEGY, LocalDate.of(2026, 6, 30)))
        .isEqualByComparingTo("0");
  }

  @Test
  void sameDayEntryAndExit_realizesNetLoss_dramShape() {
    // Same-day buy 3 @ 2.3533 then sell 2 @ 1.84 -> day-scoped matches its OWN basis (no phantom).
    String dram = "DRAM  260717C00030000";
    insert("BUY", dram, 3, "FILLED", 3, "2.3533", "2026-06-29T14:00:00Z");
    insert("SELL", dram, 2, "FILLED", 2, "1.84", "2026-06-29T18:00:00Z");

    assertThat(svc.computeRealizedPnl(TENANT, STRATEGY, LocalDate.of(2026, 6, 29)))
        .isEqualByComparingTo("-102.66");
    assertThat(svc.computeRealizedPnlAllTime(TENANT, STRATEGY)).isEqualByComparingTo("-102.66");
  }

  @Test
  void crossDay_dayScopedMatchesRealBasis_notPhantomProceeds() {
    // Entry day-1, exit day-2 (both FILLED). Both all-time AND day-scoped-on-day-2 now FIFO-match
    // the real cross-day basis (#276 §4 phantom fix) — the exit's realized is attributed to day-2.
    insert("BUY", null, 2, "FILLED", 2, "2.30", "2026-05-14T14:00:00Z");
    insert("SELL", null, 2, "FILLED", 2, "3.10", "2026-05-15T17:30:00Z");

    // All-time: 2 * (3.10 - 2.30) * 100 = 160.
    assertThat(svc.computeRealizedPnlAllTime(TENANT, STRATEGY)).isEqualByComparingTo("160.00");
    // Day-scoped on day-2: real basis 2 * (3.10 - 2.30) * 100 = 160 (was phantom 620 pre-fix).
    assertThat(svc.computeRealizedPnl(TENANT, STRATEGY, LocalDate.of(2026, 5, 15)))
        .isEqualByComparingTo("160.00");
    // Day-scoped on day-1 (entry day, no exit): 0 — the loss/gain lands on the exit day only.
    assertThat(svc.computeRealizedPnl(TENANT, STRATEGY, LocalDate.of(2026, 5, 14)))
        .isEqualByComparingTo("0");
  }

  @Test
  void nullBrokerTarget_returnsZero_noThrow() {
    DbStrategyConfigReader registry = mock(DbStrategyConfigReader.class);
    when(registry.brokerTarget(TENANT, STRATEGY)).thenReturn(null);
    RealizedPnlCalculator unconfigured =
        new RealizedPnlCalculator(mock(BrokerDataSourceRouter.class), registry);

    assertThat(unconfigured.computeRealizedPnlAllTime(TENANT, STRATEGY)).isEqualByComparingTo("0");
    assertThat(unconfigured.computeRealizedPnl(TENANT, STRATEGY, LocalDate.of(2026, 6, 29)))
        .isEqualByComparingTo("0");
  }

  private void insert(
      String side,
      String optionSymbol,
      Integer qty,
      String state,
      Integer filledQty,
      String avgFillPrice,
      String filledAtIso) {
    dsl.execute(
        "INSERT INTO order_intent_journal (tenant_id, strategy_id, intent_key, option_symbol,"
            + " side, qty, state, filled_qty, avg_fill_price, filled_at) VALUES (?, ?, ?, ?, ?, ?,"
            + " ?, ?, ?::numeric, ?::timestamptz)",
        TENANT,
        STRATEGY,
        UUID.randomUUID(),
        optionSymbol,
        side,
        qty,
        state,
        filledQty,
        avgFillPrice,
        filledAtIso);
  }
}
