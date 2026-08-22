package com.ohmytradeagent.orchestrator.alert.tradecontext;

import java.math.BigDecimal;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

/**
 * Issue #783: the {@code trade_context} store in the DASHBOARD database, written as the
 * least-privilege {@code trade_context_writer} role (V13 — SELECT/INSERT/UPDATE only, no DELETE).
 *
 * <p><b>Why its own connection, not a bean.</b> The dashboard DB is a different database from the
 * orchestrator's own, and exposing a second {@code DSLContext} bean would break every existing
 * unqualified {@code DSLContext} injection point in this service. Instead this repository builds a
 * private connection-per-statement datasource ({@link DriverManagerDataSource} — self-healing
 * across a Postgres restart, and at a ~1/min poll cadence pooling would buy nothing). DARK by
 * default: {@code trade-context.recorder.enabled=false} leaves {@link #enabled()} false and every
 * method a no-op, so the repo default deploys with no dashboard-DB creds at all.
 *
 * <p><b>Idempotency lives in the SQL, not in caller discipline:</b> the entry INSERT is {@code ON
 * CONFLICT (signal_id, tenant_id) DO NOTHING} (a poller restart re-observing an open position
 * cannot duplicate the row or overwrite the original snapshot), and the MFE/MAE ratchet is {@code
 * GREATEST}/{@code LEAST} against the stored value (replaying an older bid cannot reset the
 * excursion). {@code TradeContextMigrationIT} (BFF module, RUN_DB_ITS-gated) proves both against a
 * real Postgres as the real role; {@code TradeContextRepositorySqlTest} pins these shapes here.
 *
 * <p>Methods may throw (a dashboard-DB outage surfaces as a RuntimeException from jOOQ); the
 * recorder wraps every call, so nothing here can reach the alert path.
 */
@Component
public class TradeContextRepository {

  private final DSLContext dsl;

  @Autowired
  public TradeContextRepository(
      @Value("${trade-context.recorder.enabled:false}") boolean enabled,
      @Value("${trade-context.db.url:}") String url,
      @Value("${trade-context.db.user:trade_context_writer}") String user,
      @Value("${trade-context.db.password:}") String password) {
    this.dsl =
        (enabled && !url.isBlank())
            ? DSL.using(new DriverManagerDataSource(url, user, password), SQLDialect.POSTGRES)
            : null;
  }

  /** Test seam: drive the SQL against an arbitrary (possibly mock) DSLContext. */
  TradeContextRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** False when the recorder is dark (flag off / no DB url) — callers must no-op. */
  public boolean enabled() {
    return dsl != null;
  }

  /** Entry row, written once per (signal, tenant); a replay is a silent no-op. */
  public void upsertEntry(TradeContextEntry e) {
    dsl.execute(
        "INSERT INTO trade_context (signal_id, tenant_id, strategy_id, workflow_id, "
            + "contract_symbol, entry_at, entry_premium, entry_qty, entry_bid, entry_ask, "
            + "entry_spread, entry_iv, entry_delta, entry_gamma, entry_theta, entry_vega, "
            + "underlying_spot, dte, moneyness, capital_weight, entry_quote_state) "
            // entry_at carries an EXPLICIT ::timestamptz cast: jOOQ's default binding sends
            // OffsetDateTime as a STRING parameter, and Postgres refuses varchar->timestamptz in
            // INSERT without one. Seen live 2026-08-22 on every entry row ("column entry_at is of
            // type timestamp with time zone but expression is of type character varying"),
            // silently swallowed by the fail-soft wrapper. The binding itself is invisible to any
            // non-Postgres test; the cast token is what TradeContextRepositorySqlTest pins.
            + "VALUES (?, ?, ?, ?, ?, ?::timestamptz, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (signal_id, tenant_id) DO NOTHING",
        e.signalId(),
        e.tenantId(),
        e.strategyId(),
        e.workflowId(),
        e.contractSymbol(),
        e.entryAt(),
        e.entryPremium(),
        e.entryQty(),
        e.entryBid(),
        e.entryAsk(),
        e.entrySpread(),
        e.iv(),
        e.delta(),
        e.gamma(),
        e.theta(),
        e.vega(),
        e.underlyingSpot(),
        e.dte(),
        e.moneyness(),
        e.capitalWeight(),
        e.quoteState());
  }

  /**
   * Per-poll premium-path update: monotonic MFE/MAE ratchet off the current BID, plus the current
   * owning workflow id (recon adoption re-mints it for the same signal) and a re-open of a row a
   * Visibility flicker may have closed early.
   */
  public void ratchet(String signalId, String tenantId, String workflowId, BigDecimal bid) {
    dsl.execute(
        "UPDATE trade_context SET "
            + "mfe_premium = GREATEST(COALESCE(mfe_premium, ?), ?), "
            + "mae_premium = LEAST(COALESCE(mae_premium, ?), ?), "
            + "workflow_id = ?, status = 'open', updated_at = now() "
            + "WHERE signal_id = ? AND tenant_id = ?",
        bid,
        bid,
        bid,
        bid,
        workflowId,
        signalId,
        tenantId);
  }

  /** All rows still marked open — the close pass diffs these against the live Visibility set. */
  public List<OpenRow> openRows() {
    return dsl.fetch(
            "SELECT signal_id, tenant_id, workflow_id, contract_symbol "
                + "FROM trade_context WHERE status = 'open'")
        .map(
            r ->
                new OpenRow(
                    r.get("signal_id", String.class),
                    r.get("tenant_id", String.class),
                    r.get("workflow_id", String.class),
                    r.get("contract_symbol", String.class)));
  }

  /**
   * Exit append for a vanished position. Realized P&L / exit reason / latency / slippage stay null
   * by design — they live in other databases and are joined at query time (see
   * docs/ops/trade-outcome-join.md and the V13 migration header).
   */
  public void close(String signalId, String tenantId, BigDecimal exitBid, BigDecimal exitIv) {
    dsl.execute(
        "UPDATE trade_context SET status = 'closed', closed_at = now(), "
            + "exit_bid = ?, exit_iv = ?, "
            + "hold_minutes = CAST(FLOOR(EXTRACT(EPOCH FROM "
            + "(now() - COALESCE(entry_at, first_observed_at))) / 60) AS BIGINT), "
            + "updated_at = now() "
            + "WHERE signal_id = ? AND tenant_id = ? AND status = 'open'",
        exitBid,
        exitIv,
        signalId,
        tenantId);
  }

  /** One open row, as the close pass needs it. */
  public record OpenRow(
      String signalId, String tenantId, String workflowId, String contractSymbol) {}
}
