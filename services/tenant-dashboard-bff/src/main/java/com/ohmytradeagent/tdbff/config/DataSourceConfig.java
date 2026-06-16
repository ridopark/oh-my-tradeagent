package com.ohmytradeagent.tdbff.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the BFF's read-only datasources by hand — net-new in this codebase (every other service has
 * a single auto-configured datasource). {@code DataSourceAutoConfiguration} and {@code
 * JooqAutoConfiguration} are excluded in {@code application.yml} so these qualified beans are the
 * only datasources/DSLContexts.
 *
 * <ul>
 *   <li>{@code orchestratorDsl} ({@code @Primary}) — reads {@code audit_log} (trades + realized
 *       PnL).
 *   <li>{@code execAlpacaPaperDsl} — reads {@code order_intent_journal} (paper order history).
 *   <li>{@code execAlpacaLiveDsl} — reads {@code order_intent_journal} (live order history); the
 *       {@code BrokerDataSourceRouter} picks paper vs live per the strategy's {@code
 *       broker_target}.
 * </ul>
 *
 * <p>Both connect as the {@code bff_readonly} role (SELECT-only grants; see the operator runbook),
 * so even a query bug cannot mutate trading state. Pools are marked {@code read-only} in
 * application.yml as defense-in-depth.
 */
@Configuration
public class DataSourceConfig {

  @Bean
  @Primary
  @ConfigurationProperties("bff.datasource.orchestrator")
  public DataSource orchestratorDataSource() {
    return new HikariDataSource();
  }

  @Bean
  @ConfigurationProperties("bff.datasource.exec-alpaca-paper")
  public DataSource execAlpacaPaperDataSource() {
    return new HikariDataSource();
  }

  @Bean
  @ConfigurationProperties("bff.datasource.exec-alpaca-live")
  public DataSource execAlpacaLiveDataSource() {
    return new HikariDataSource();
  }

  // Explicit @Qualifier on both params: with two DataSource beans and orchestrator marked @Primary,
  // Spring resolves @Primary BEFORE parameter-name matching — so without the qualifier
  // execAlpacaPaperDsl would silently wrap the orchestrator datasource and OrdersReader would query
  // the wrong DB. The qualifier pins each DSLContext to its intended datasource.
  @Bean
  @Primary
  public DSLContext orchestratorDsl(
      @Qualifier("orchestratorDataSource") DataSource orchestratorDataSource) {
    return DSL.using(orchestratorDataSource, SQLDialect.POSTGRES);
  }

  @Bean
  public DSLContext execAlpacaPaperDsl(
      @Qualifier("execAlpacaPaperDataSource") DataSource execAlpacaPaperDataSource) {
    return DSL.using(execAlpacaPaperDataSource, SQLDialect.POSTGRES);
  }

  @Bean
  public DSLContext execAlpacaLiveDsl(
      @Qualifier("execAlpacaLiveDataSource") DataSource execAlpacaLiveDataSource) {
    return DSL.using(execAlpacaLiveDataSource, SQLDialect.POSTGRES);
  }
}
