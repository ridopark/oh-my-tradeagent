package com.ohmytradeagent.orchestrator.alert.tradecontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Issue #783: pins the SQL SHAPES that carry the idempotency invariants, without Docker (the
 * behavioral proof against a real Postgres as the real role is {@code TradeContextMigrationIT} in
 * the BFF module, RUN_DB_ITS-gated):
 *
 * <ul>
 *   <li>the entry INSERT must be {@code ON CONFLICT (signal_id, tenant_id) DO NOTHING} — a poller
 *       restart re-observing an open position cannot duplicate the row or overwrite the snapshot;
 *   <li>the MFE/MAE ratchet must be {@code GREATEST}/{@code LEAST} against the stored value — a
 *       replayed older bid cannot reset the excursion;
 *   <li>the close must be guarded on {@code status = 'open'} — re-closing is a no-op, and closed
 *       rows keep their exit snapshot.
 * </ul>
 */
class TradeContextRepositorySqlTest {

  private DSLContext dsl;
  private TradeContextRepository repo;

  @BeforeEach
  void setUp() {
    dsl = mock(DSLContext.class);
    repo = new TradeContextRepository(dsl);
  }

  private static TradeContextEntry entry() {
    return new TradeContextEntry(
        "sig-1",
        "acme",
        "copytrade-v1",
        "wf-1",
        "NVDA  270115C00140000",
        OffsetDateTime.parse("2026-08-21T13:40:00Z"),
        new BigDecimal("2.00"),
        3,
        new BigDecimal("1.90"),
        new BigDecimal("2.10"),
        new BigDecimal("0.20"),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "ok");
  }

  @Test
  void entryInsertIsAnOnConflictDoNothingUpsertOnTheSignalTenantKey() {
    repo.upsertEntry(entry());

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(dsl).execute(sql.capture(), any(Object[].class));
    assertThat(sql.getValue()).contains("INSERT INTO trade_context");
    assertThat(sql.getValue()).contains("ON CONFLICT (signal_id, tenant_id) DO NOTHING");
  }

  @Test
  void ratchetIsMonotonic_greatestForMfe_leastForMae_andReopensTheRow() {
    repo.ratchet("sig-1", "acme", "wf-1", new BigDecimal("1.50"));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(dsl).execute(sql.capture(), any(Object[].class));
    assertThat(sql.getValue()).contains("mfe_premium = GREATEST(COALESCE(mfe_premium,");
    assertThat(sql.getValue()).contains("mae_premium = LEAST(COALESCE(mae_premium,");
    assertThat(sql.getValue()).contains("status = 'open'");
    assertThat(sql.getValue()).contains("WHERE signal_id = ? AND tenant_id = ?");
  }

  @Test
  void closeIsGuardedOnOpenStatus_andNeverTouchesRealizedOutcomeColumns() {
    repo.close("sig-1", "acme", new BigDecimal("0.90"), new BigDecimal("0.71"));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(dsl).execute(sql.capture(), any(Object[].class));
    assertThat(sql.getValue()).contains("status = 'closed'");
    assertThat(sql.getValue()).contains("AND status = 'open'");
    assertThat(sql.getValue()).contains("hold_minutes");
    // Realized outcome is joined at query time (docs/ops/trade-outcome-join.md), never written
    // here — the recorder has no source it is allowed to read them from.
    assertThat(sql.getValue()).doesNotContain("realized_pnl").doesNotContain("exit_reason");
  }

  @Test
  void openRowsMapsTheFourColumnsTheClosePassNeeds() {
    DSLContext create = DSL.using(SQLDialect.POSTGRES);
    Field<String> sig = DSL.field("signal_id", String.class);
    Field<String> ten = DSL.field("tenant_id", String.class);
    Field<String> wf = DSL.field("workflow_id", String.class);
    Field<String> occ = DSL.field("contract_symbol", String.class);
    Result<Record> result = create.newResult(List.of(sig, ten, wf, occ));
    Record r = create.newRecord(List.of(sig, ten, wf, occ));
    r.set(sig, "sig-1");
    r.set(ten, "acme");
    r.set(wf, "wf-1");
    r.set(occ, "NVDA  270115C00140000");
    result.add(r);
    when(dsl.fetch(anyString())).thenReturn(result);

    List<TradeContextRepository.OpenRow> rows = repo.openRows();

    assertThat(rows)
        .containsExactly(
            new TradeContextRepository.OpenRow("sig-1", "acme", "wf-1", "NVDA  270115C00140000"));
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(dsl).fetch(sql.capture());
    assertThat(sql.getValue()).contains("WHERE status = 'open'");
  }

  @Test
  void darkByDefault_disabledConfigMeansNoDslAndEnabledFalse() {
    TradeContextRepository dark = new TradeContextRepository(false, "jdbc:x", "u", "p");
    assertThat(dark.enabled()).isFalse();
    TradeContextRepository noUrl = new TradeContextRepository(true, "", "u", "p");
    assertThat(noUrl.enabled()).isFalse();
  }
}
