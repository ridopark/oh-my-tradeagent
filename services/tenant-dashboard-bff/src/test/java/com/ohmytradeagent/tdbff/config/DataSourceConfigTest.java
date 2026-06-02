package com.ohmytradeagent.tdbff.config;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.impl.DataSourceConnectionProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

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
}
