package com.ohmytradeagent.tdbff.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader.TenantStrategyBrokerTarget;
import com.ohmytradeagent.tdbff.promotion.LivePromotionReader.PromotionStatus;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record4;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockExecuteContext;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link LivePromotionReader} using jOOQ's mock JDBC, so it runs in CI rather
 * than behind the Docker-gated ITs.
 *
 * <p>The case that matters most is {@link #readFailure_yieldsUnknown_neverActive()}: this reader
 * exists because a silent seven-day trading halt was invisible, so a reader that reports a
 * healthy-looking "active" when it cannot actually read the approvals would reproduce the original
 * failure with extra confidence.
 */
class LivePromotionReaderTest {

  private static final String TENANT = "prod-soonwon";
  private static final OffsetDateTime NOW =
      OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC);

  /** Replays canned approval rows, or fails the read when constructed with an exception. */
  private static final class Approvals implements MockDataProvider {
    private final List<Object[]> rows;
    private final SQLException failWith;

    Approvals(List<Object[]> rows, SQLException failWith) {
      this.rows = rows;
      this.failWith = failWith;
    }

    @Override
    public MockResult[] execute(MockExecuteContext ctx) throws SQLException {
      if (failWith != null) {
        throw failWith;
      }
      DSLContext create = DSL.using(SQLDialect.POSTGRES);
      Field<String> strategy = DSL.field("strategy_id", String.class);
      Field<String> target = DSL.field("broker_target", String.class);
      Field<OffsetDateTime> at = DSL.field("occurred_at", OffsetDateTime.class);
      Field<String> op = DSL.field("operator_id", String.class);
      Result<Record4<String, String, OffsetDateTime, String>> r =
          create.newResult(strategy, target, at, op);
      for (Object[] row : rows) {
        r.add(
            create
                .newRecord(strategy, target, at, op)
                .values(
                    (String) row[0], (String) row[1], (OffsetDateTime) row[2], (String) row[3]));
      }
      return new MockResult[] {new MockResult(r.size(), r)};
    }
  }

  private static LivePromotionReader reader(
      List<TenantStrategyBrokerTarget> strategies,
      List<Object[]> approvals,
      SQLException failWith) {
    DbStrategyConfigReader configReader = mock(DbStrategyConfigReader.class);
    when(configReader.listAll()).thenReturn(strategies);
    DSLContext dsl =
        DSL.using(new MockConnection(new Approvals(approvals, failWith)), SQLDialect.POSTGRES);
    return new LivePromotionReader(dsl, configReader);
  }

  private static TenantStrategyBrokerTarget live(String strategyId) {
    return new TenantStrategyBrokerTarget(TENANT, strategyId, "alpaca-live", true);
  }

  private static Object[] approvedDaysAgo(String strategyId, long days) {
    return new Object[] {strategyId, "alpaca-live", NOW.minusDays(days), "ridopark@gmail.com"};
  }

  @Test
  void noApprovalRow_isAbsent() {
    List<PromotionStatus> out =
        reader(List.of(live("copytrade-v1")), List.of(), null).statuses(TENANT, NOW);

    assertThat(out)
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.status()).isEqualTo("absent");
              assertThat(s.approvedAt()).isNull();
              assertThat(s.expiresAt()).isNull();
            });
  }

  @Test
  void freshApproval_isActive() {
    List<PromotionStatus> out =
        reader(
                List.of(live("copytrade-v1")),
                List.<Object[]>of(approvedDaysAgo("copytrade-v1", 5)),
                null)
            .statuses(TENANT, NOW);

    assertThat(out)
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.status()).isEqualTo("active");
              assertThat(s.daysRemaining()).isEqualTo(25L);
              assertThat(s.operatorId()).isEqualTo("ridopark@gmail.com");
            });
  }

  @Test
  void approvalInsideTheWarnWindow_isExpiring() {
    // 25 days old => 5 days left, inside the 7-day warn window. This is the state that would have
    // caught the 2026-09-21 expiry BEFORE it silently blocked three live tenants for a week.
    List<PromotionStatus> out =
        reader(
                List.of(live("copytrade-v1")),
                List.<Object[]>of(approvedDaysAgo("copytrade-v1", 25)),
                null)
            .statuses(TENANT, NOW);

    assertThat(out)
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.status()).isEqualTo("expiring");
              assertThat(s.daysRemaining()).isEqualTo(5L);
            });
  }

  @Test
  void approvalPastTheTtl_isStale() {
    List<PromotionStatus> out =
        reader(
                List.of(live("copytrade-v1")),
                List.<Object[]>of(approvedDaysAgo("copytrade-v1", 31)),
                null)
            .statuses(TENANT, NOW);

    assertThat(out).singleElement().satisfies(s -> assertThat(s.status()).isEqualTo("stale"));
  }

  @Test
  void readFailure_yieldsUnknown_neverActive() {
    List<PromotionStatus> out =
        reader(
                List.of(live("copytrade-v1")),
                List.of(),
                new SQLException("permission denied for table audit_log"))
            .statuses(TENANT, NOW);

    assertThat(out)
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.status()).isEqualTo("unknown");
              // The whole point: an unreadable approvals table must not render as cleared to trade,
              // and
              // must not be confused with "nothing was ever approved" either.
              assertThat(s.status()).isNotEqualTo("active");
              assertThat(s.status()).isNotEqualTo("absent");
            });
  }

  @Test
  void paperAndDisabledStrategiesAreOmitted() {
    List<TenantStrategyBrokerTarget> strategies =
        List.of(
            new TenantStrategyBrokerTarget(TENANT, "paper-v1", "alpaca-paper", true),
            new TenantStrategyBrokerTarget(TENANT, "disabled-v1", "alpaca-live", false),
            new TenantStrategyBrokerTarget("other-tenant", "copytrade-v1", "alpaca-live", true));

    assertThat(reader(strategies, List.of(), null).statuses(TENANT, NOW)).isEmpty();
  }
}
