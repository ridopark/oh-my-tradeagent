package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Phase 1 (Fork A) watchlist fan-out controller slice test — the byte-for-byte sibling of {@link
 * CopytradeFanoutControllerTest}, re-bound to the watchlist strategy. {@link
 * WatchlistFanoutController} is a plain jOOQ read against the orchestrator {@code strategy_config}
 * table. We back it with a jOOQ {@link MockDataProvider} that (a) captures the rendered SQL + bind
 * values so the enabled-predicate and the parameterized strategy id are asserted, and (b) applies
 * the same {@code IS DISTINCT FROM 'false'} + strategy filter to an in-memory dataset so
 * inclusion/exclusion (enabled true, enabled absent, exclude enabled:false, exclude non-watchlist)
 * is exercised end-to-end through the controller.
 */
class WatchlistFanoutControllerTest {

  private static final String STRATEGY = "watchlist-trigger-v1";

  /** A fixture row as it would live in {@code strategy_config}. {@code enabled} null == absent. */
  private record Row(String tenant, String strategy, String enabled) {}

  private final AtomicReference<String> capturedSql = new AtomicReference<>();
  private final AtomicReference<Object[]> capturedBindings = new AtomicReference<>();

  /**
   * Builds a DSLContext whose "database" is the given dataset, filtered exactly as the production
   * query filters: {@code strategy_id = <bound strategy> AND (config->>'enabled') IS DISTINCT FROM
   * 'false'}. The enabled rule (null/absent and 'true' pass, 'false' excluded) mirrors the SQL and
   * the runtime gate that rejects only {@code Boolean.FALSE}.
   */
  private DSLContext dslOver(List<Row> dataset) {
    DSLContext render = DSL.using(SQLDialect.POSTGRES);
    Field<String> tenantF = DSL.field("tenant_id", String.class);
    Field<String> stratF = DSL.field("strategy_id", String.class);
    MockDataProvider provider =
        ctx -> {
          capturedSql.set(ctx.sql());
          capturedBindings.set(ctx.bindings());
          String boundStrategy =
              ctx.bindings().length > 0 ? String.valueOf(ctx.bindings()[0]) : null;
          Result<Record2<String, String>> result = render.newResult(tenantF, stratF);
          for (Row row : dataset) {
            boolean enabled = !"false".equals(row.enabled());
            if (row.strategy().equals(boundStrategy) && enabled) {
              result.add(render.newRecord(tenantF, stratF).values(row.tenant(), row.strategy()));
            }
          }
          return new MockResult[] {new MockResult(result.size(), result)};
        };
    return DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> targetsOf(ResponseEntity<Map<String, Object>> resp) {
    return (List<Map<String, Object>>) resp.getBody().get("targets");
  }

  @Test
  void returnsEnabledAndAbsentWatchlistTenants_excludesDisabledAndNonWatchlist() {
    List<Row> dataset =
        new ArrayList<>(
            List.of(
                new Row("acme", STRATEGY, "true"), // enabled → included
                new Row("beta", STRATEGY, null), // enabled absent → included
                new Row("gamma", STRATEGY, "false"), // disabled → excluded
                new Row("delta", "copytrade-v1", "true"))); // non-watchlist → excluded
    WatchlistFanoutController controller =
        new WatchlistFanoutController(dslOver(dataset), STRATEGY);

    ResponseEntity<Map<String, Object>> resp = controller.targets();

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map<String, Object>> targets = targetsOf(resp);
    assertThat(targets)
        .containsExactlyInAnyOrder(
            Map.of("tenant_id", "acme", "strategy_id", STRATEGY),
            Map.of("tenant_id", "beta", "strategy_id", STRATEGY));
    assertThat(resp.getBody()).containsEntry("count", 2);
  }

  @Test
  void queryUsesEnabledPredicate_andParameterizesStrategyId() {
    WatchlistFanoutController controller =
        new WatchlistFanoutController(dslOver(List.of()), STRATEGY);

    controller.targets();

    assertThat(capturedSql.get().toLowerCase()).contains("is distinct from 'false'");
    // The strategy id is a bind parameter (not a hardcoded literal) so it is configurable.
    assertThat(capturedBindings.get()).contains(STRATEGY);
    assertThat(capturedSql.get()).doesNotContain("'" + STRATEGY + "'");
  }

  @Test
  void honorsConfiguredStrategyId_notWatchlistHardcoded() {
    String customStrategy = "some-other-watchlist";
    List<Row> dataset =
        new ArrayList<>(
            List.of(
                new Row("acme", customStrategy, "true"),
                new Row("beta", STRATEGY, "true"))); // default strategy → excluded
    WatchlistFanoutController controller =
        new WatchlistFanoutController(dslOver(dataset), customStrategy);

    ResponseEntity<Map<String, Object>> resp = controller.targets();

    assertThat(targetsOf(resp))
        .containsExactly(Map.of("tenant_id", "acme", "strategy_id", customStrategy));
    assertThat(resp.getBody()).containsEntry("count", 1);
  }

  @Test
  void emptyRegistry_returnsEmptyTargets_countZero() {
    WatchlistFanoutController controller =
        new WatchlistFanoutController(dslOver(List.of()), STRATEGY);

    ResponseEntity<Map<String, Object>> resp = controller.targets();

    assertThat(targetsOf(resp)).isEmpty();
    assertThat(resp.getBody()).containsEntry("count", 0);
  }
}
