package com.ohmytradeagent.tdbff.positions;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockExecuteContext;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link TradeContextSpotReader} using jOOQ's mock JDBC, so this runs in CI
 * rather than behind the Docker-gated ITs.
 *
 * <p>This code is worth testing precisely because its sibling {@link TradeContextPeakReader} failed
 * silently for months: it swallows every read failure by design, so a broken query, a missing grant
 * or a bad key produces an empty result that looks exactly like "no data recorded yet".
 */
class TradeContextSpotReaderTest {

  private static final String WF_MANUAL =
      "t-prod_real/s-copytrade-v1/pos/SMCI  261120C00050000/manual:abc";
  private static final String WF_COPIED =
      "t-prod_real/s-copytrade-v1/pos/SMCI  261120C00050000/chat-messages-1:0";

  /** Captures the SQL + bind values the reader issues, and replays a canned result. */
  private static final class Capture implements MockDataProvider {
    final AtomicReference<String> sql = new AtomicReference<>();
    final AtomicReference<Object[]> binds = new AtomicReference<>();
    private final List<String[]> rows;
    private final SQLException failWith;

    Capture(List<String[]> rows, SQLException failWith) {
      this.rows = rows;
      this.failWith = failWith;
    }

    @Override
    public MockResult[] execute(MockExecuteContext ctx) throws SQLException {
      sql.set(ctx.sql());
      binds.set(ctx.bindings());
      if (failWith != null) {
        throw failWith;
      }
      DSLContext create = DSL.using(SQLDialect.POSTGRES);
      Field<String> sig = DSL.field("signal_id", String.class);
      Field<BigDecimal> spot = DSL.field("underlying_spot", BigDecimal.class);
      Result<Record2<String, BigDecimal>> r = create.newResult(sig, spot);
      for (String[] row : rows) {
        Record2<String, BigDecimal> rec = create.newRecord(sig, spot);
        rec.value1(row[0]);
        rec.value2(row[1] == null ? null : new BigDecimal(row[1]));
        r.add(rec);
      }
      return new MockResult[] {new MockResult(r.size(), r)};
    }
  }

  private static TradeContextSpotReader readerOver(Capture capture) {
    return new TradeContextSpotReader(
        Optional.of(DSL.using(new MockConnection(capture), SQLDialect.POSTGRES)));
  }

  @Test
  void keysResultByWorkflowIdAndScopesTheQueryToTheTenant() {
    Capture capture =
        new Capture(
            List.<String[]>of(
                new String[] {"manual:abc", "38.30"}, new String[] {"chat-messages-1:0", "38.305"}),
            null);

    Map<String, BigDecimal> out =
        readerOver(capture).entrySpotByWorkflowId("prod_real", List.of(WF_MANUAL, WF_COPIED));

    assertThat(out.get(WF_MANUAL)).isEqualByComparingTo("38.30");
    assertThat(out.get(WF_COPIED)).isEqualByComparingTo("38.305");
    // The tenant predicate is the ONLY thing separating tenants in this table (no RLS) — V14 makes
    // that explicit, so pin it here rather than trusting a future edit.
    assertThat(capture.sql.get()).contains("tenant_id = ?");
    assertThat(capture.binds.get()[0]).isEqualTo("prod_real");
  }

  @Test
  void twoPositionsSharingASignalIdBothGetTheSpot() {
    // One row in the table, two live workflows parsing to the same signal id: neither may silently
    // lose its entry spot to the other.
    String wfOther = "t-prod_real/s-other-v1/pos/SMCI  261120C00050000/manual:abc";
    Capture capture = new Capture(List.<String[]>of(new String[] {"manual:abc", "38.30"}), null);

    Map<String, BigDecimal> out =
        readerOver(capture).entrySpotByWorkflowId("prod_real", List.of(WF_MANUAL, wfOther));

    assertThat(out).containsOnlyKeys(WF_MANUAL, wfOther);
    assertThat(out.values()).allSatisfy(v -> assertThat(v).isEqualByComparingTo("38.30"));
  }

  @Test
  void aNullRecordedSpotIsOmittedRatherThanMappedToNull() {
    Capture capture = new Capture(List.<String[]>of(new String[] {"manual:abc", null}), null);

    assertThat(readerOver(capture).entrySpotByWorkflowId("prod_real", List.of(WF_MANUAL)))
        .isEmpty();
  }

  @Test
  void aReadFailureDegradesToEmptyRatherThanPropagating() {
    // 42501 is exactly what this reader hit before V14 granted SELECT — and what TradeContextPeak-
    // Reader has been hitting unnoticed. It must produce an empty map, never a failed /live.
    Capture capture =
        new Capture(
            List.<String[]>of(),
            new SQLException("permission denied for table trade_context", "42501"));

    assertThat(readerOver(capture).entrySpotByWorkflowId("prod_real", List.of(WF_MANUAL)))
        .isEmpty();
  }

  @Test
  void noDatasourceOrNoParsableWorkflowIdQueriesNothing() {
    // The dashboard-writer datasource is conditional; absent, the reader must not touch the DB.
    assertThat(
            new TradeContextSpotReader(Optional.empty())
                .entrySpotByWorkflowId("prod_real", List.of(WF_MANUAL)))
        .isEmpty();

    Capture capture = new Capture(List.<String[]>of(), null);
    assertThat(readerOver(capture).entrySpotByWorkflowId("prod_real", List.of("not-a-workflow-id")))
        .isEmpty();
    assertThat(capture.sql.get()).as("no signal ids parsed -> no query at all").isNull();

    Capture empty = new Capture(List.<String[]>of(), null);
    assertThat(readerOver(empty).entrySpotByWorkflowId("prod_real", List.of())).isEmpty();
    assertThat(empty.sql.get()).isNull();
  }
}
