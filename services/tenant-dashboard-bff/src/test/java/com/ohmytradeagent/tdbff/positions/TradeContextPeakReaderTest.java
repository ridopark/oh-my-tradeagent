package com.ohmytradeagent.tdbff.positions;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

/**
 * Fail-soft contract of the #778 {@code trade_context.mfe_premium} read. The three load-bearing
 * states are: value available, table ABSENT (#783 / PR #786 unmerged — the relation may not exist
 * at runtime), and row absent (the recorder ships dark). None of them may ever escape as an
 * exception: a failed read degrades the arm flow to recent-only, it must not break it.
 */
class TradeContextPeakReaderTest {

  private static final String WF_ID = "t-acme/s-copytrade-v1/pos/AAPL260727C00330000/sig1";

  private static DSLContext mockDsl(MockDataProvider provider) {
    return DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
  }

  private static MockResult[] oneRow(BigDecimal mfe) {
    DSLContext create = DSL.using(SQLDialect.POSTGRES);
    Field<BigDecimal> field = DSL.field("mfe_premium", BigDecimal.class);
    Result<Record1<BigDecimal>> result = create.newResult(field);
    result.add(create.newRecord(field).values(mfe));
    return new MockResult[] {new MockResult(1, result)};
  }

  private static MockResult[] noRows() {
    DSLContext create = DSL.using(SQLDialect.POSTGRES);
    Field<BigDecimal> field = DSL.field("mfe_premium", BigDecimal.class);
    return new MockResult[] {new MockResult(0, create.newResult(field))};
  }

  private static TradeContextPeakReader reader(MockDataProvider provider) {
    return new TradeContextPeakReader(Optional.of(mockDsl(provider)));
  }

  @Test
  void returnsMfeWhenRowPresent_keyedByTenantAndParsedSignalId() {
    MockDataProvider provider =
        ctx -> {
          // The row key is (tenant_id, signal_id) — the same key the #783 recorder writes, with
          // the signal id parsed from the workflow id, NOT the raw workflow id.
          assertThat(ctx.sql()).contains("tenant_id = ?").contains("signal_id = ?");
          assertThat(ctx.bindings()).containsExactly("acme", "sig1");
          return oneRow(new BigDecimal("3.40"));
        };
    assertThat(reader(provider).mfePremium("acme", WF_ID))
        .isEqualByComparingTo(new BigDecimal("3.40"));
  }

  @Test
  void missingTable_returnsNull_neverThrows() {
    // #786 unmerged: the relation may simply not exist. jOOQ wraps the SQLException in a
    // DataAccessException; the reader must swallow it and offer no anchor.
    MockDataProvider provider =
        ctx -> {
          throw new SQLException("ERROR: relation \"trade_context\" does not exist", "42P01");
        };
    assertThat(reader(provider).mfePremium("acme", WF_ID)).isNull();
  }

  @Test
  void missingRow_returnsNull() {
    assertThat(reader(mr -> noRows()).mfePremium("acme", WF_ID)).isNull();
  }

  @Test
  void nullOrNonPositiveMfe_returnsNull() {
    // A worthless-since-entry position has mfe at/below zero; fire = peak * (1 - giveback) on such
    // an anchor is meaningless, so no anchor is offered.
    assertThat(reader(mr -> oneRow(null)).mfePremium("acme", WF_ID)).isNull();
    assertThat(reader(mr -> oneRow(BigDecimal.ZERO)).mfePremium("acme", WF_ID)).isNull();
    assertThat(reader(mr -> oneRow(new BigDecimal("-0.05"))).mfePremium("acme", WF_ID)).isNull();
  }

  @Test
  void absentDatasource_returnsNull_withoutQuerying() {
    // dashboardWriterDsl is conditional on dashboard.writer.enabled — a cluster without it must
    // degrade to recent-only, not fail to boot or NPE.
    TradeContextPeakReader r = new TradeContextPeakReader(Optional.empty());
    assertThat(r.mfePremium("acme", WF_ID)).isNull();
  }

  @Test
  void unparseableWorkflowId_returnsNull_andNeverQueries() {
    AtomicInteger queries = new AtomicInteger();
    MockDataProvider provider =
        ctx -> {
          queries.incrementAndGet();
          return noRows();
        };
    TradeContextPeakReader r = reader(provider);
    assertThat(r.mfePremium("acme", null)).isNull();
    assertThat(r.mfePremium("acme", "t-acme/s-copytrade-v1/killswitch")).isNull();
    assertThat(r.mfePremium("acme", "t-acme/s-x/pos/OCCONLY")).isNull();
    assertThat(queries).hasValue(0);
  }

  /**
   * Pin of the LOCAL mirror of the unmerged #786 {@code WorkflowIds.entrySignalIdFromPosition} —
   * same cases as its contract-side test, so the two cannot drift silently before consolidation.
   */
  @Test
  void entrySignalIdParsing_mirrorsThe786ContractHelper() {
    assertThat(TradeContextPeakReader.entrySignalIdFromPosition(WF_ID)).isEqualTo("sig1");
    // Watchlist signal ids contain slashes of their own; everything after the OCC belongs to them.
    assertThat(
            TradeContextPeakReader.entrySignalIdFromPosition(
                "t-acme/s-watchlist-trigger-v1/pos/SPY   260609P00731000/wl/2026-06-09/SPY/P"))
        .isEqualTo("wl/2026-06-09/SPY/P");
    assertThat(TradeContextPeakReader.entrySignalIdFromPosition(null)).isNull();
    assertThat(TradeContextPeakReader.entrySignalIdFromPosition("")).isNull();
    assertThat(TradeContextPeakReader.entrySignalIdFromPosition("t-acme/s-x/pos//sig")).isNull();
    assertThat(TradeContextPeakReader.entrySignalIdFromPosition("t-acme/s-x/pos/OCC/")).isNull();
  }
}
