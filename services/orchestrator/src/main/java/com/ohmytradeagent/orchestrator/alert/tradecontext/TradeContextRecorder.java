package com.ohmytradeagent.orchestrator.alert.tradecontext;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.alert.floorbreach.MarketDataOptionQuoteClient;
import com.ohmytradeagent.orchestrator.alert.floorbreach.MarketDataOptionQuoteClient.OptionGreeksSnapshot;
import com.ohmytradeagent.orchestrator.alert.floorbreach.MarketDataOptionQuoteClient.OptionQuote;
import com.ohmytradeagent.orchestrator.domain.OccSymbol;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
import com.ohmytradeagent.orchestrator.workflows.PositionState;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Issue #783: the trade-context recorder — the #779 floor-breach detector doubling as the
 * per-signal data collector. Driven by {@code FloorBreachAlertLoop} with what the loop already
 * holds (position state + current quote); persists into the dashboard DB's {@code trade_context}
 * table via {@link TradeContextRepository}:
 *
 * <ul>
 *   <li><b>Entry row</b>, on first observation of a new position: the current NBBO plus ONE extra
 *       greeks snapshot and one underlying-spot read (the unbackfillable fields), DTE/moneyness
 *       derived from the OCC, and the {@code capital_weight} sizing input from {@code
 *       strategy_config}. A missing quote writes nulls + {@code entry_quote_state='unknown'}.
 *   <li><b>Per poll</b>: the MFE/MAE premium-path ratchet off the current BID (monotonic in SQL, so
 *       restarts and replays can never reset it).
 *   <li><b>On disappearance from Visibility</b> (complete-listing passes only): the exit append —
 *       one last NBBO/IV snapshot, hold minutes, {@code status='closed'}.
 * </ul>
 *
 * <p><b>HARD INVARIANT (#783, same as #779):</b> observation-only. This package holds no Temporal
 * client, places/modifies/cancels nothing, and its only writes go to the dashboard DB. Enforced by
 * {@code TradeContextNoTradingActionGuardTest}.
 *
 * <p><b>Fail-soft everywhere:</b> both public methods catch-all and never throw — a recorder
 * failure must NEVER break the floor-breach alert path that hosts it (the loop belt-and-suspenders
 * wraps the calls too). Positions whose workflow id carries no entry signal id (malformed/legacy)
 * are skipped: the row key is {@code (signal_id, tenant_id)} and a row we cannot key we must not
 * write.
 */
@Component
public class TradeContextRecorder {

  private static final Logger log = LoggerFactory.getLogger(TradeContextRecorder.class);

  private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");

  private final TradeContextRepository repo;
  private final MarketDataOptionQuoteClient quoteClient;

  /** The orchestrator's own DB (strategy_config). May be null in test/boot envs — fail-soft. */
  private final DSLContext dsl;

  /**
   * Keys ({@code tenant|signal}) already known to have an entry row, so steady-state polls skip the
   * entry path (and its extra market-data calls) without a DB read. After a restart the set is cold
   * and the entry INSERT runs once more — the SQL's ON CONFLICT makes that a silent no-op.
   */
  private final Set<String> knownKeys = ConcurrentHashMap.newKeySet();

  @Autowired
  public TradeContextRecorder(
      TradeContextRepository repo,
      MarketDataOptionQuoteClient quoteClient,
      @Autowired(required = false) DSLContext dsl) {
    this.repo = repo;
    this.quoteClient = quoteClient;
    this.dsl = dsl;
  }

  /** One observed open position on this poll. Never throws. */
  public void observe(TenantStrategy ts, String wfId, PositionState state, OptionQuote quote) {
    if (!repo.enabled()) {
      return;
    }
    try {
      String signalId = WorkflowIds.entrySignalIdFromPosition(wfId);
      if (signalId == null) {
        log.debug("trade-context: no entry signal id in wf={}; skipping", wfId);
        return;
      }
      String key = key(ts.tenantId(), signalId);
      if (!knownKeys.contains(key)) {
        repo.upsertEntry(entrySnapshot(ts, wfId, signalId, state, quote));
        knownKeys.add(key);
      }
      if (quote != null && quote.bid() != null) {
        repo.ratchet(signalId, ts.tenantId(), wfId, quote.bid());
      }
    } catch (RuntimeException e) {
      log.warn("trade-context: observe failed wf={}: {}", wfId, e.getMessage());
    }
  }

  /**
   * Close pass: append the exit snapshot to every open row whose workflow is absent from {@code
   * seenWorkflowIds}. Callers must invoke this only off a COMPLETE Visibility listing — a partial
   * one would close rows for positions that are still live (the ratchet re-opens on the next poll,
   * but each flicker costs a bogus exit snapshot). Never throws.
   */
  public void closeVanished(Set<String> seenWorkflowIds) {
    if (!repo.enabled()) {
      return;
    }
    try {
      for (TradeContextRepository.OpenRow row : repo.openRows()) {
        if (seenWorkflowIds.contains(row.workflowId())) {
          continue;
        }
        try {
          OptionQuote quote = quoteClient.optionQuote(row.contractSymbol());
          OptionGreeksSnapshot greeks = quoteClient.optionGreeks(row.contractSymbol());
          repo.close(
              row.signalId(),
              row.tenantId(),
              quote == null ? null : quote.bid(),
              greeks == null ? null : greeks.iv());
          knownKeys.remove(key(row.tenantId(), row.signalId()));
        } catch (RuntimeException e) {
          // Per-row fail-soft: one bad close must not starve the rest of the pass.
          log.warn(
              "trade-context: close failed signal={} tenant={}: {}",
              row.signalId(),
              row.tenantId(),
              e.getMessage());
        }
      }
    } catch (RuntimeException e) {
      log.warn("trade-context: close pass failed: {}", e.getMessage());
    }
  }

  /** The one-time entry snapshot: NBBO from the poll + one greeks call + one spot read. */
  private TradeContextEntry entrySnapshot(
      TenantStrategy ts, String wfId, String signalId, PositionState state, OptionQuote quote) {
    String occ = state.contractSymbol();
    OptionGreeksSnapshot greeks = quoteClient.optionGreeks(occ);
    BigDecimal spot = quoteClient.underlyingSpot(OccSymbol.underlying(occ));
    BigDecimal bid = quote == null ? null : quote.bid();
    BigDecimal ask = quote == null ? null : quote.ask();
    LocalDate expiry = OccSymbol.expiryOf(occ);
    Integer dte =
        expiry == null ? null : (int) ChronoUnit.DAYS.between(LocalDate.now(MARKET_TZ), expiry);
    return new TradeContextEntry(
        signalId,
        ts.tenantId(),
        ts.strategyId(),
        wfId,
        occ,
        state.entryAt(),
        state.entryPremium(),
        state.remainingQty(),
        bid,
        ask,
        bid == null || ask == null ? null : ask.subtract(bid),
        greeks == null ? null : greeks.iv(),
        greeks == null ? null : greeks.delta(),
        greeks == null ? null : greeks.gamma(),
        greeks == null ? null : greeks.theta(),
        greeks == null ? null : greeks.vega(),
        spot,
        dte,
        moneyness(spot, OccSymbol.strikeOf(occ)),
        capitalWeight(ts.tenantId(), ts.strategyId()),
        quote == null ? "unknown" : "ok");
  }

  /** spot / strike (calls ITM above 1, puts ITM below 1), or null when either side is missing. */
  private static BigDecimal moneyness(BigDecimal spot, BigDecimal strike) {
    if (spot == null || strike == null || strike.signum() == 0) {
      return null;
    }
    return spot.divide(strike, 6, RoundingMode.HALF_UP);
  }

  /**
   * The {@code capital_weight} sizing input from the {@code strategy_config} row, read the same way
   * {@code FloorBreachThresholdResolver} reads its threshold — best-effort, null on any
   * error/absence (no cache: this runs once per position lifetime, not per poll).
   */
  private BigDecimal capitalWeight(String tenantId, String strategyId) {
    if (dsl == null || tenantId == null || strategyId == null) {
      return null;
    }
    try {
      Record row =
          dsl.fetchOne(
              "SELECT config->>'capital_weight' AS cw "
                  + "FROM strategy_config WHERE tenant_id = ? AND strategy_id = ?",
              tenantId,
              strategyId);
      if (row == null) {
        return null;
      }
      String raw = row.get("cw", String.class);
      return raw == null || raw.isBlank() ? null : new BigDecimal(raw.trim());
    } catch (RuntimeException e) {
      log.warn(
          "trade-context: capital_weight lookup failed tenant={} strategy={}: {}",
          tenantId,
          strategyId,
          e.getMessage());
      return null;
    }
  }

  private static String key(String tenantId, String signalId) {
    return tenantId + "|" + signalId;
  }
}
