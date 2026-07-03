package com.ohmytradeagent.tdbff.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.impl.DataSourceConnectionProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Guards the multi-datasource wiring. {@code orchestratorDataSource} is {@code @Primary}, and
 * Spring resolves {@code @Primary} before parameter-name matching — so without an explicit
 * {@code @Qualifier} the {@code execAlpacaPaperDsl} bean would wrap the orchestrator datasource and
 * {@code OrdersReader} would silently query the wrong DB. This test would fail in that case.
 */
class DataSourceConfigTest {

  private static DataSource dataSourceOf(DSLContext dsl) {
    return ((DataSourceConnectionProvider) dsl.configuration().connectionProvider()).dataSource();
  }

  @Test
  void eachDslContextWrapsItsOwnDataSource() {
    // No DB connection happens here — Hikari pools are lazy until first getConnection().
    try (var ctx = new AnnotationConfigApplicationContext(DataSourceConfig.class)) {
      DataSource orchestratorDs = (DataSource) ctx.getBean("orchestratorDataSource");
      DataSource execDs = (DataSource) ctx.getBean("execAlpacaPaperDataSource");
      DSLContext orchestratorDsl = (DSLContext) ctx.getBean("orchestratorDsl");
      DSLContext execDsl = (DSLContext) ctx.getBean("execAlpacaPaperDsl");

      assertThat(dataSourceOf(orchestratorDsl)).isSameAs(orchestratorDs);
      assertThat(dataSourceOf(execDsl)).isSameAs(execDs); // not the @Primary orchestrator one
      assertThat(dataSourceOf(execDsl)).isNotSameAs(orchestratorDs);
    }
  }

  /**
   * The write path is DARK by default: without {@code dashboard.writer.enabled=true} the {@code
   * dashboard_writer} DataSource/DSLContext beans must NOT exist. This is the guard that the
   * feature cannot activate in the repo default (where no writer creds are present) — a writer pool
   * built with a blank {@code DASHBOARD_WRITER_PASSWORD} would otherwise sit ready to connect.
   */
  @Test
  void writerDatasourceIsAbsentWhenFlagUnset() {
    try (var ctx = new AnnotationConfigApplicationContext(DataSourceConfig.class)) {
      assertThat(ctx.containsBean("dashboardWriterDataSource")).isFalse();
      assertThat(ctx.containsBean("dashboardWriterDsl")).isFalse();
    }
  }

  /**
   * With the flag on, the writer beans are constructed (lazy Hikari pool — no DB connection here).
   */
  @Test
  void writerDatasourceIsPresentWhenFlagEnabled() {
    try (var ctx = new AnnotationConfigApplicationContext()) {
      ctx.getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "writer-flag",
                  Map.of(
                      "dashboard.writer.enabled", "true",
                      "bff.datasource.dashboard-writer.jdbc-url",
                          "jdbc:postgresql://localhost:5432/dashboard",
                      "bff.datasource.dashboard-writer.username", "dashboard_writer",
                      "bff.datasource.dashboard-writer.password", "unused-in-test")));
      ctx.register(DataSourceConfig.class);
      ctx.refresh();

      assertThat(ctx.containsBean("dashboardWriterDataSource")).isTrue();
      DSLContext writerDsl = (DSLContext) ctx.getBean("dashboardWriterDsl");
      DataSource writerDs = (DataSource) ctx.getBean("dashboardWriterDataSource");
      assertThat(dataSourceOf(writerDsl)).isSameAs(writerDs);
    }
  }
}
