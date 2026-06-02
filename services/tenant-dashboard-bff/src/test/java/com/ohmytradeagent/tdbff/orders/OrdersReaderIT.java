package com.ohmytradeagent.tdbff.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.tdbff.config.BrokerDataSourceRouter;
import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.platform.YamlStrategyRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
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
 * SQL-level coverage for {@link OrdersReader} against a real Postgres — the key assertion is TENANT
 * ISOLATION: the {@code tenant_id = ? AND strategy_id IN (...)} scoping must never return another
 * tenant's rows nor a strategy outside the tenant's resolved set. The resolver/registry/router
 * collaborators are mocked to point the reader at the Testcontainers datasource; the SQL itself is
 * exercised for real. Gated on {@code RUN_DB_ITS=true}; {@code order_intent_journal} DDL is inlined
 * (the BFF does not own that schema).
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class OrdersReaderIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static Connection conn;
  private static DSLContext dsl;

  private final TenantStrategyResolver strategyResolver = mock(TenantStrategyResolver.class);
  private final YamlStrategyRegistry strategyRegistry = mock(YamlStrategyRegistry.class);
  private final BrokerDataSourceRouter router = mock(BrokerDataSourceRouter.class);
  private OrdersReader reader;

  @BeforeAll
  static void initDb() throws Exception {
    conn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dsl = DSL.using(conn, SQLDialect.POSTGRES);
    dsl.execute(
        "CREATE TABLE order_intent_journal ("
            + "  intent_key VARCHAR(192) PRIMARY KEY,"
            + "  signal_id VARCHAR(96),"
            + "  tenant_id VARCHAR(64) NOT NULL,"
            + "  strategy_id VARCHAR(64) NOT NULL,"
            + "  broker_target VARCHAR(32),"
            + "  option_symbol VARCHAR(32),"
            + "  side VARCHAR(4),"
            + "  qty BIGINT,"
            + "  limit_price NUMERIC(18,4),"
            + "  state VARCHAR(16),"
            + "  broker_order_id VARCHAR(96),"
            + "  recorded_at TIMESTAMPTZ NOT NULL,"
            + "  submitted_at TIMESTAMPTZ,"
            + "  filled_qty BIGINT,"
            + "  avg_fill_price NUMERIC(18,4),"
            + "  filled_at TIMESTAMPTZ,"
            + "  last_error TEXT)");
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (conn != null) {
      conn.close();
    }
  }

  @BeforeEach
  void reset() {
    dsl.execute("DELETE FROM order_intent_journal");
    reader = new OrdersReader(strategyResolver, strategyRegistry, router);
    // Tenant `dev` has one strategy s1 on alpaca-paper; route that broker to the test datasource.
    when(strategyResolver.strategyIdsForTenant("dev")).thenReturn(List.of("s1"));
    when(strategyRegistry.brokerTarget("dev", "s1")).thenReturn("alpaca-paper");
    when(router.dslFor("alpaca-paper")).thenReturn(dsl);
  }

  @Test
  void scopesToRequestedTenantAndStrategy_andSortsNewestFirst() {
    // TWO in-scope rows so the cross-broker re-sort in orders() actually runs (a 1-element list
    // never invokes the comparator — that gap hid a Timestamp->OffsetDateTime ClassCastException).
    insert("ok-old", "dev", "s1", "2026-05-14T14:00:00Z");
    insert("ok-new", "dev", "s1", "2026-05-14T18:00:00Z");
    insert("leak", "other", "s1", "2026-05-14T15:00:00Z"); // different tenant
    insert("wrong-strat", "dev", "s2", "2026-05-14T16:00:00Z"); // strategy not in resolved set

    List<Map<String, Object>> items = reader.orders("dev", 100);

    assertThat(items).hasSize(2);
    // newest first — exercises byRecordedAtDesc on the OffsetDateTime-typed recorded_at column.
    assertThat(items).extracting(m -> m.get("intent_key")).containsExactly("ok-new", "ok-old");
  }

  private void insert(String intentKey, String tenant, String strategy, String recordedAtIso) {
    dsl.execute(
        "INSERT INTO order_intent_journal (intent_key, signal_id, tenant_id, strategy_id,"
            + " broker_target, option_symbol, side, qty, state, recorded_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)",
        intentKey,
        "sig-" + intentKey,
        tenant,
        strategy,
        "alpaca-paper",
        "SYM   260516C00040000",
        "BUY",
        1L,
        "FILLED",
        recordedAtIso);
  }
}
