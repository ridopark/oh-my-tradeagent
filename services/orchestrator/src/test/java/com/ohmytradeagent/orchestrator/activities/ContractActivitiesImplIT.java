package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import com.ohmytradeagent.orchestrator.domain.ContractResolveInput;
import com.ohmytradeagent.orchestrator.domain.ContractResolveResult;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import org.flywaydb.core.Flyway;
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
 * Gated on {@code RUN_DB_ITS=true} so a local {@code mvn verify} on a Docker-Desktop-on-WSL2
 * workstation (where Testcontainers' Unix-socket probe rejects the proxy socket) skips this test
 * cleanly. CI runs it because the workflow sets that env var.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class ContractActivitiesImplIT {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static java.sql.Connection conn;
  private static DSLContext dsl;
  private ContractActivitiesImpl contract;

  @BeforeAll
  static void initDb() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    conn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dsl = DSL.using(conn, SQLDialect.POSTGRES);
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (conn != null) conn.close();
  }

  @BeforeEach
  void truncate() {
    dsl.deleteFrom(table("option_symbol_cache")).execute();
    contract = new ContractActivitiesImpl(dsl);
  }

  @Test
  void resolve_onCacheMiss_generatesOccAndInsertsRow() {
    ContractResolveInput input =
        new ContractResolveInput(
            "dev", "NVDA", LocalDate.of(2026, 5, 16), new BigDecimal("140"), "C");

    ContractResolveResult r = contract.resolve(input);

    assertThat(r.optionSymbol()).isEqualTo("NVDA  260516C00140000");
    assertThat(r.source()).isEqualTo(ContractResolveResult.SOURCE_GENERATED);

    Long rows = dsl.select(count()).from(table("option_symbol_cache")).fetchOneInto(Long.class);
    assertThat(rows).isEqualTo(1L);
  }

  @Test
  void resolve_onCacheHit_returnsCachedRowWithoutReinsert() {
    ContractResolveInput input =
        new ContractResolveInput(
            "dev", "SPY", LocalDate.of(2026, 12, 31), new BigDecimal("420"), "P");

    contract.resolve(input);
    ContractResolveResult second = contract.resolve(input);

    assertThat(second.optionSymbol()).isEqualTo("SPY   261231P00420000");

    Long rows =
        dsl.select(count())
            .from(table("option_symbol_cache"))
            .where(field("ticker", String.class).eq("SPY"))
            .fetchOneInto(Long.class);
    assertThat(rows).isEqualTo(1L);
  }
}
