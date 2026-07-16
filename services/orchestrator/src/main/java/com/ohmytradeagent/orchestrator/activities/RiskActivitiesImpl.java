package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.WatchlistTriggerPayload;
import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.domain.RejectionReason;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import com.ohmytradeagent.orchestrator.domain.Sizing;
import com.ohmytradeagent.orchestrator.domain.StrategyConfigs;
import com.ohmytradeagent.orchestrator.workflows.AccountKillSwitchWorkflow;
import com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.failure.ApplicationFailure;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Risk gate Activity. Phase 5 wires the kill-switch read via {@code
 * KillSwitchWorkflow.killswitchState()} — any failure (workflow-not-found, query rejection, service
 * timeout) fails CLOSED with {@link RejectionReason#KILL_SWITCH_UNAVAILABLE}. Tripped or
 * within-cooldown state rejects with the corresponding reason.
 *
 * <p>Issue #6 adds six portfolio-level sub-gates that run after the existing per-order gates:
 * {@code notional_cap_pct_of_capital_base}, {@code same_underlying_count}, {@code
 * sector_concentration_cap}, {@code daily_trade_count}, {@code drawdown_velocity_threshold}, and
 * {@code pre_trade_check}. Each is strictly opt-in via the corresponding {@code StrategyConfig}
 * field; null/absent config short-circuits the gate so existing strategies remain unchanged.
 *
 * <p>Issue #336: the notional-cap field {@code notional_cap_pct_of_capital_base} is canonical;
 * {@code notional_cap_pct_of_equity} is a DEPRECATED alias resolved by {@link
 * #resolveNotionalCapPct} — old-only and both-equal paths emit the {@link
 * #DEPRECATED_EQUITY_FIELD_COUNTER_NAME} counter + a {@code log.warn}; both-set-unequal fails
 * CLOSED with {@link RejectionReason#NOTIONAL_CAP_EXCEEDED} detail {@code ambiguous_cap_config}.
 */
@Component
public class RiskActivitiesImpl implements RiskActivities {

  static final Duration FUTURE_DATE_TOLERANCE = Duration.ofSeconds(5);

  /**
   * Phase F4B: the long ceiling for the headroom-overflow clamp in {@link
   * #notionalCapHeadroomContracts}.
   */
  private static final BigDecimal LONG_MAX_AS_DECIMAL = BigDecimal.valueOf(Long.MAX_VALUE);

  /**
   * Issue #336 deprecation signal. Incremented (and a {@code log.warn} emitted) whenever a strategy
   * config still sets the deprecated {@code notional_cap_pct_of_equity} alias. Follows the
   * #329/#331 risk-counter idiom: {@code Counter.builder(...).register(meterRegistry)}, a {@code
   * _total}-suffixed name, and per-tag caching keyed on the {@code tenant}/{@code strategy} tags.
   */
  static final String DEPRECATED_EQUITY_FIELD_COUNTER_NAME =
      "notional_cap_deprecated_equity_field_total";

  /**
   * C2 (single-account-loss-rule): incremented on every {@link
   * RejectionReason#KILL_SWITCH_UNAVAILABLE} fail-closed in {@link #checkAccountKillSwitch} /
   * {@link #checkKillSwitch}. Tagged {@code scope=account|strategy} and {@code
   * reason=no_client|null_state|<ExceptionClass>} so a flaky account-scope KS query (now the sole
   * daily-loss breaker) is visible in metrics instead of silently fail-closing every entry.
   */
  static final String KILL_SWITCH_UNAVAILABLE_COUNTER_NAME = "risk.kill_switch_unavailable";

  private static final Logger log = LoggerFactory.getLogger(RiskActivitiesImpl.class);

  private final PositionCounter positionCounter;
  private final Clock clock;
  private final WorkflowClient workflowClient;
  private final PortfolioSnapshot portfolioSnapshot;
  private final SectorResolver sectorResolver;
  private final DailyTradeCounter dailyTradeCounter;
  private final DrawdownVelocitySampler drawdownVelocitySampler;
  private final PreTradeCheckActivity preTradeCheckActivity;
  private final MeterRegistry meterRegistry;
  private final ConcurrentMap<String, Counter> deprecatedEquityFieldCounters =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Counter> killSwitchUnavailableCounters =
      new ConcurrentHashMap<>();

  /**
   * Test/back-compat constructor: legacy callers that only exercise the per-order gates can omit
   * the Issue #6 collaborators. Each portfolio gate is opt-in via config, so substituting no-op
   * defaults here keeps existing tests untouched while production wiring goes through the full
   * constructor.
   */
  public RiskActivitiesImpl(
      PositionCounter positionCounter, Clock clock, WorkflowClient workflowClient) {
    this(
        positionCounter,
        clock,
        workflowClient,
        RiskCollaboratorDefaults.permissivePortfolioSnapshot(),
        SectorResolver.CONFIG_BACKED,
        RiskCollaboratorDefaults.zeroDailyTradeCounter(),
        RiskCollaboratorDefaults.zeroDrawdownSampler(),
        RiskCollaboratorDefaults.permissivePreTradeCheck());
  }

  /**
   * Test/back-compat constructor: the Issue #6 collaborators without a {@link MeterRegistry}. The
   * Issue #336 deprecation counter falls back to a local {@link SimpleMeterRegistry} so existing
   * eight-arg test sites stay unchanged while production goes through the {@code @Autowired}
   * constructor with the Spring-managed registry.
   */
  public RiskActivitiesImpl(
      PositionCounter positionCounter,
      Clock clock,
      WorkflowClient workflowClient,
      PortfolioSnapshot portfolioSnapshot,
      SectorResolver sectorResolver,
      DailyTradeCounter dailyTradeCounter,
      DrawdownVelocitySampler drawdownVelocitySampler,
      PreTradeCheckActivity preTradeCheckActivity) {
    this(
        positionCounter,
        clock,
        workflowClient,
        portfolioSnapshot,
        sectorResolver,
        dailyTradeCounter,
        drawdownVelocitySampler,
        preTradeCheckActivity,
        new SimpleMeterRegistry());
  }

  @Autowired
  public RiskActivitiesImpl(
      PositionCounter positionCounter,
      Clock clock,
      WorkflowClient workflowClient,
      PortfolioSnapshot portfolioSnapshot,
      SectorResolver sectorResolver,
      DailyTradeCounter dailyTradeCounter,
      DrawdownVelocitySampler drawdownVelocitySampler,
      PreTradeCheckActivity preTradeCheckActivity,
      MeterRegistry meterRegistry) {
    this.positionCounter = positionCounter;
    this.clock = clock;
    this.workflowClient = workflowClient;
    this.portfolioSnapshot = portfolioSnapshot;
    this.sectorResolver = sectorResolver;
    this.dailyTradeCounter = dailyTradeCounter;
    this.drawdownVelocitySampler = drawdownVelocitySampler;
    this.preTradeCheckActivity = preTradeCheckActivity;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public RiskDecision checkEntry(
      CopytradeSignalPayload payload, StrategyConfig config, PreTradeCheckResult preTradeResult) {
    // Legacy path: notional from unadjusted mirror, preserved bit-exact for pre-#111 replays. No
    // workflow-supplied equity → gate falls back to the PortfolioSnapshot seam.
    return checkEntryInternal(
        payload, config, preTradeResult, entryNotional(payload.getPrice(), 1L), null);
  }

  @Override
  public RiskDecision checkEntryWithLimit(
      CopytradeSignalPayload payload,
      StrategyConfig config,
      PreTradeCheckResult preTradeResult,
      BigDecimal limit,
      BigDecimal accountCash) {
    // Production callers always pass priced.limit(); fall back to mirror keeps unit-test ergonomic.
    BigDecimal price = limit != null ? limit : payload.getPrice();
    return checkEntryInternal(
        payload, config, preTradeResult, entryNotional(price, 1L), accountCash);
  }

  private RiskDecision checkEntryInternal(
      CopytradeSignalPayload payload,
      StrategyConfig config,
      PreTradeCheckResult preTradeResult,
      BigDecimal entryNotional,
      BigDecimal accountCash) {
    if (!config.getAuthorWhitelist().contains(payload.getAuthor())) {
      return RiskDecision.rejected(
          RejectionReason.AUTHOR_NOT_WHITELISTED, "author=" + payload.getAuthor());
    }

    OffsetDateTime now = OffsetDateTime.now(clock);
    OffsetDateTime postedAt = payload.getPostedAt();
    if (postedAt.isAfter(now.plus(FUTURE_DATE_TOLERANCE))) {
      Duration skew = Duration.between(now, postedAt);
      return RiskDecision.rejected(
          RejectionReason.INVALID_TIMESTAMP, "future_skew_secs=" + skew.toSeconds());
    }

    long maxAgeSecs = resolveMaxSignalAgeSecs(payload, config);
    long ageSecs = Duration.between(postedAt, now).getSeconds();
    if (ageSecs > maxAgeSecs) {
      return RiskDecision.rejected(
          RejectionReason.SIGNAL_TOO_OLD, "age_secs=" + ageSecs + " max=" + maxAgeSecs);
    }

    return runStrategyAgnosticGates(
        config,
        payload.getTenantId(),
        payload.getStrategyId(),
        payload.getTicker(),
        entryNotional,
        accountCash,
        preTradeResult,
        now);
  }

  /**
   * Strategy-agnostic risk gates 4-6: kill switch, max_positions, and the Issue #6 portfolio-level
   * stream. Extracted from {@link #checkEntryInternal} so strategies that are NOT copytrade (e.g.
   * the watchlist-trigger strategy via {@link #checkWatchlistEntry}) can run the same shared gates
   * WITHOUT the copytrade-only author_whitelist / future-skew / max_signal_age pre-gates (1-3).
   *
   * <p>The agnostic gates read only {@code tenantId}, {@code strategyId}, and the underlying {@code
   * ticker} from the (former) payload, plus the already-resolved {@code entryNotional}, {@code
   * accountCash}, and {@code preTradeResult} — none of which is copytrade-specific. The verdict is
   * bit-identical to the previous inline stream (verified by the unchanged copytrade risk suite).
   */
  private RiskDecision runStrategyAgnosticGates(
      StrategyConfig config,
      String tenantId,
      String strategyId,
      String ticker,
      BigDecimal entryNotional,
      BigDecimal accountCash,
      PreTradeCheckResult preTradeResult,
      OffsetDateTime now) {
    RiskDecision killSwitchDecision = checkKillSwitch(tenantId, strategyId, now);
    if (killSwitchDecision != null) {
      return killSwitchDecision;
    }
    // Also consult the account-scope kill switch so an account-cap trip halts NEW entries (not just
    // flattens). Per-strategy first (preserves the existing verdict order), then account.
    RiskDecision accountKillSwitchDecision = checkAccountKillSwitch(tenantId, now);
    if (accountKillSwitchDecision != null) {
      return accountKillSwitchDecision;
    }

    long openPositions = positionCounter.countOpen(tenantId, strategyId);
    if (openPositions >= config.getMaxPositions()) {
      return RiskDecision.rejected(RejectionReason.MAX_POSITIONS_EXCEEDED, "open=" + openPositions);
    }

    // Issue #6 portfolio-level gates. Each is config-gated so a strategy that opts out keeps the
    // pre-Issue-#6 behavior. Order matters only for which reason wins when multiple gates fail;
    // the order below mirrors the recommendation list in issue #6. Positions are fetched once and
    // shared across the gates that need them (production impl is a Temporal Visibility query).
    PortfolioContext ctx =
        new PortfolioContext(
            tenantId, strategyId, ticker, config, preTradeResult, entryNotional, accountCash);
    return Stream.<Supplier<RiskDecision>>of(
            () -> checkNotionalCap(ctx),
            () -> checkSameUnderlyingCount(ctx),
            () -> checkSectorConcentration(ctx),
            () -> checkDailyTradeCount(ctx),
            () -> checkDrawdownVelocity(ctx),
            () -> checkPreTradeCheck(ctx))
        .map(Supplier::get)
        .filter(Objects::nonNull)
        .findFirst()
        .orElseGet(RiskDecision::approved);
  }

  @Override
  public RiskDecision checkWatchlistEntry(
      WatchlistTriggerPayload payload,
      StrategyConfig config,
      PreTradeCheckResult preTradeResult,
      BigDecimal limit,
      BigDecimal accountCash) {
    // Watchlist-trigger entry: runs ONLY the strategy-agnostic gates (kill switch, max_positions,
    // Issue #6 portfolio stream). The copytrade-only author_whitelist / future-skew /
    // max_signal_age
    // pre-gates are NOT applied — a watchlist trigger has no author and no posted-at timestamp.
    // limit is the BTO max-cost (option premium); production always supplies it. The ZERO fallback
    // keeps the unit-test surface ergonomic and only loosens the notional cap — the cap still fails
    // closed on a null/zero accountCash.
    BigDecimal price = limit != null ? limit : BigDecimal.ZERO;
    return runStrategyAgnosticGates(
        config,
        payload.getTenantId(),
        payload.getStrategyId(),
        payload.getTicker(),
        entryNotional(price, 1L),
        accountCash,
        preTradeResult,
        OffsetDateTime.now(clock));
  }

  /**
   * Per-call cache for the portfolio gates. Holds the open-position list (fetched lazily so a
   * deployment that disables all position-aware gates skips the Visibility query entirely) plus the
   * resolved entry notional, which several gates need. The {@code entryNotional} is pre-computed by
   * the entry-point method ({@link #checkEntry} = mirror price, {@link #checkEntryWithLimit} =
   * slip-adjusted limit) so the gate bodies stay source-agnostic.
   */
  private final class PortfolioContext {
    final String tenantId;
    final String strategyId;
    final String ticker;
    final StrategyConfig config;
    final PreTradeCheckResult preTradeResult;
    final BigDecimal entryNotional;
    // Workflow-supplied account cash balance (from the broker-<target> AccountSnapshotActivity
    // /v2/account 'cash'). Issue #323: this is the cash component of the notional-cap gate's
    // MTM-stable cost-basis capital base (cash + sum_open_notional), NOT net-liq equity. Null when
    // not dispatched (legacy checkEntry path / unit tests) — the notional-cap gate then falls back
    // to the PortfolioSnapshot seam keyed on broker_target (ZERO sentinel → fail closed).
    final BigDecimal accountCash;
    private List<PortfolioSnapshot.OpenPosition> openPositions;

    PortfolioContext(
        String tenantId,
        String strategyId,
        String ticker,
        StrategyConfig config,
        PreTradeCheckResult preTradeResult,
        BigDecimal entryNotional,
        BigDecimal accountCash) {
      this.tenantId = tenantId;
      this.strategyId = strategyId;
      this.ticker = ticker;
      this.config = config;
      this.preTradeResult = preTradeResult;
      this.entryNotional = entryNotional;
      this.accountCash = accountCash;
    }

    // WARNING — fail-closed seam (#325, hardening #318). A throw from
    // portfolioSnapshot.openPositions(...) (a Visibility error in VisibilityPortfolioSnapshot) MUST
    // propagate out of here and fail checkEntry/checkEntryWithLimit so the workflow never reaches
    // placeOrder. Do NOT wrap this call in try/catch returning List.of(): an empty list means
    // sum_open_notional=0, which loosens the notional_cap_pct_of_equity cap and flips the gate
    // fail-OPEN (permitting trades it should reject). Unlike checkKillSwitch
    // (RiskActivitiesImpl.java ~448-461), which fails closed *explicitly* with
    // KILL_SWITCH_UNAVAILABLE, this gate fails closed *only by the absence of a catch* here and in
    // VisibilityPortfolioSnapshot.openPositions(). The null-coalesce below normalizes a null return
    // to an empty list (a no-op default impl); it is NOT a swallow of a thrown error.
    List<PortfolioSnapshot.OpenPosition> openPositions() {
      if (openPositions == null) {
        List<PortfolioSnapshot.OpenPosition> positions =
            portfolioSnapshot.openPositions(tenantId, strategyId);
        openPositions = positions == null ? List.of() : positions;
      }
      return openPositions;
    }
  }

  /**
   * notional_cap_pct_of_capital_base (canonical; {@code notional_cap_pct_of_equity} is a deprecated
   * alias resolved by {@link #resolveNotionalCapPct}, #336): reject when {@code (sum_open_notional
   * + new_notional) > cap_pct * (cash + sum_open_notional)}.
   *
   * <p><b>MTM-stable cost-basis denominator (#323).</b> The denominator is the cost-basis capital
   * base {@code cash + sum_open_notional}, NOT the net-liq (MTM) {@code equity} it replaced. Both
   * the numerator's {@code sum_open_notional} (entry premium × remaining qty × multiplier, summed
   * over the tenant's running book) and the denominator's {@code sum_open_notional} are the SAME
   * tenant-account-wide cost-basis term — so numerator and denominator move together and the cap is
   * MTM-stable: it no longer loosens on an appreciating long-options book or tightens on a bleeding
   * one, and introduces no new market-data dependency. The cash component is threaded from the
   * broker {@code /v2/account} read ({@code cash}) over the AccountSnapshot dispatch seam; {@code
   * sum_open_notional} is the tenant-account-wide sum from the #323 {@link
   * VisibilityPortfolioSnapshot} (a per-strategy {@code TenantStrategy='...'} equality query
   * unioned across the tenant's strategies).
   *
   * <p><b>Fail-closed.</b> A null/zero cash (an unavailable account read, or a pre-#323 producer
   * that omits {@code cash}) yields a zero-or-undercounted capital base; the gate rejects with
   * {@code cash_unavailable} rather than passing an unbounded cap — preserving the #317
   * fail-closed-on-zero contract. The capital base is zero only when BOTH cash is zero AND there
   * are no open positions, which is itself a reject (cannot size against a zero base).
   */
  private RiskDecision checkNotionalCap(PortfolioContext ctx) {
    BigDecimal capPct;
    try {
      capPct = resolveNotionalCapPct(ctx.config);
    } catch (AmbiguousCapConfigException e) {
      // Issue #336 fail-closed: both notional_cap fields set to different values. Reject rather
      // than silently picking one — an ambiguous risk-gate config must never admit a trade.
      return RiskDecision.rejected(RejectionReason.NOTIONAL_CAP_EXCEEDED, "ambiguous_cap_config");
    }
    if (capPct == null) {
      return null;
    }
    // The cash term is the workflow-supplied account cash dispatched from the
    // broker-<broker_target> AccountSnapshotActivity. When it is unavailable (legacy checkEntry
    // path, non-dispatch providers) the gate fails closed via the guard below rather than
    // substituting a proxy: the only other account figure on hand is the PortfolioSnapshot
    // net-liq seam, and net-liq >= cash would ENLARGE capitalBase (cash + sumOpenNotional) and
    // LOOSEN the cap — admitting notional the real cash term would reject. So net-liq is never read
    // here; an absent cash term simply rejects.
    BigDecimal cash = ctx.accountCash;
    if (cash == null || cash.signum() <= 0) {
      return RiskDecision.rejected(RejectionReason.NOTIONAL_CAP_EXCEEDED, "cash_unavailable");
    }
    BigDecimal sumOpenNotional = sumOpenNotional(ctx);
    BigDecimal capitalBase = cash.add(sumOpenNotional);
    BigDecimal cap = capitalBase.multiply(capPct);
    BigDecimal projected = sumOpenNotional.add(ctx.entryNotional);
    if (projected.compareTo(cap) > 0) {
      return RiskDecision.rejected(
          RejectionReason.NOTIONAL_CAP_EXCEEDED,
          "notional=" + projected.toPlainString() + " cap=" + cap.toPlainString());
    }
    return null;
  }

  /**
   * Issue #336: resolve the notional-cap fraction from the canonical {@code
   * notional_cap_pct_of_capital_base} and the DEPRECATED alias {@code notional_cap_pct_of_equity}.
   * The returned value flows into {@link #checkNotionalCap}'s gate math unchanged — only the field
   * <i>source</i> changes here, never the math.
   *
   * <ul>
   *   <li>canonical set, alias null → return canonical (normal path).
   *   <li>canonical null, alias set → emit the deprecation signal (counter + warn), return alias.
   *   <li>both set, equal → emit the deprecation signal, return the (equal) value.
   *   <li>both set, unequal → throw {@link AmbiguousCapConfigException} so the gate fails CLOSED.
   *   <li>both null → return null (gate disabled — existing opt-in behavior).
   * </ul>
   */
  private BigDecimal resolveNotionalCapPct(StrategyConfig config) {
    BigDecimal capBase = config.getNotionalCapPctOfCapitalBase();
    BigDecimal equity = config.getNotionalCapPctOfEquity();
    // Both null → gate disabled. Shared with the workflow's AccountSnapshot-dispatch guard via
    // StrategyConfigs.notionalCapConfigured so enablement never diverges (#336 regression guard).
    if (!StrategyConfigs.notionalCapConfigured(config)) {
      return null;
    }
    if (equity == null) {
      // capBase != null → canonical path.
      return capBase;
    }
    if (capBase == null) {
      emitDeprecatedEquityFieldSignal(config);
      return equity;
    }
    if (capBase.compareTo(equity) != 0) {
      throw new AmbiguousCapConfigException();
    }
    emitDeprecatedEquityFieldSignal(config);
    return capBase;
  }

  /**
   * Issue #336 deprecation signal: increment the {@link #DEPRECATED_EQUITY_FIELD_COUNTER_NAME}
   * Micrometer counter (tagged by tenant/strategy) and {@code log.warn} naming the strategy so
   * operators see they must migrate to {@code notional_cap_pct_of_capital_base}. {@code
   * checkNotionalCap} is an {@code @Activity} (not workflow code), so this side effect carries no
   * Temporal-replay concern.
   */
  private void emitDeprecatedEquityFieldSignal(StrategyConfig config) {
    String tenant = config.getTenantId();
    String strategy = config.getStrategyId();
    Counter counter =
        deprecatedEquityFieldCounters.computeIfAbsent(
            tenant + "/" + strategy,
            key ->
                Counter.builder(DEPRECATED_EQUITY_FIELD_COUNTER_NAME)
                    .description(
                        "Strategy configs still setting the deprecated notional_cap_pct_of_equity alias (#336); migrate to notional_cap_pct_of_capital_base.")
                    .tag("tenant", tenant)
                    .tag("strategy", strategy)
                    .register(meterRegistry));
    counter.increment();
    log.warn(
        "DEPRECATED notional_cap_pct_of_equity set for tenant={} strategy={}; migrate to notional_cap_pct_of_capital_base (#336, removal tracked in #338)",
        tenant,
        strategy);
  }

  /**
   * Issue #336 fail-closed sentinel: thrown by {@link #resolveNotionalCapPct} when BOTH
   * notional-cap fields are set to different values. Caught in {@link #checkNotionalCap}, which
   * rejects the entry rather than silently picking one field's value.
   */
  private static final class AmbiguousCapConfigException extends RuntimeException {
    AmbiguousCapConfigException() {
      super(null, null, false, false);
    }
  }

  private RiskDecision checkSameUnderlyingCount(PortfolioContext ctx) {
    Long cap = ctx.config.getSameUnderlyingCount();
    if (cap == null) {
      return null;
    }
    String ticker = ctx.ticker;
    long matching =
        ctx.openPositions().stream()
            .filter(p -> Objects.equals(p.underlyingTicker(), ticker))
            .count();
    if (matching >= cap) {
      return RiskDecision.rejected(
          RejectionReason.SAME_UNDERLYING_LIMIT,
          "ticker=" + ticker + " count=" + matching + " max=" + cap);
    }
    return null;
  }

  /**
   * sector_concentration_cap: bound concurrent positions per sector. Unmapped tickers resolve to
   * {@link SectorResolver#UNKNOWN_SECTOR} and are exempt — this keeps a strategy without a
   * sector_overrides map from accidentally rejecting every entry.
   */
  private RiskDecision checkSectorConcentration(PortfolioContext ctx) {
    Long cap = ctx.config.getSectorConcentrationCap();
    if (cap == null) {
      return null;
    }
    String sector = sectorResolver.resolve(ctx.ticker, ctx.config);
    if (SectorResolver.UNKNOWN_SECTOR.equals(sector)) {
      return null;
    }
    long matching =
        ctx.openPositions().stream()
            .map(p -> sectorResolver.resolve(p.underlyingTicker(), ctx.config))
            .filter(sector::equals)
            .count();
    if (matching >= cap) {
      return RiskDecision.rejected(
          RejectionReason.SECTOR_CONCENTRATION_EXCEEDED,
          "sector=" + sector + " count=" + matching + " max=" + cap);
    }
    return null;
  }

  private RiskDecision checkDailyTradeCount(PortfolioContext ctx) {
    Long cap = ctx.config.getDailyTradeCount();
    if (cap == null) {
      return null;
    }
    LocalDate today = OffsetDateTime.now(clock).toLocalDate();
    long count = dailyTradeCounter.count(ctx.tenantId, ctx.strategyId, today);
    if (count >= cap) {
      return RiskDecision.rejected(
          RejectionReason.DAILY_TRADE_COUNT_EXCEEDED, "count=" + count + " max=" + cap);
    }
    return null;
  }

  private RiskDecision checkDrawdownVelocity(PortfolioContext ctx) {
    BigDecimal threshold = ctx.config.getDrawdownVelocityThreshold();
    if (threshold == null) {
      return null;
    }
    BigDecimal rate = drawdownVelocitySampler.sampleLossRatePerMinute(ctx.tenantId, ctx.strategyId);
    if (rate == null) {
      // Fail closed on a missing sample so a broken sampler can't quietly skip the gate.
      return RiskDecision.rejected(RejectionReason.DRAWDOWN_VELOCITY_EXCEEDED, "rate_unavailable");
    }
    if (rate.compareTo(threshold) >= 0) {
      return RiskDecision.rejected(
          RejectionReason.DRAWDOWN_VELOCITY_EXCEEDED,
          "rate=" + rate.toPlainString() + " max=" + threshold.toPlainString());
    }
    return null;
  }

  /**
   * pre_trade_check: consume the workflow-supplied {@link PreTradeCheckResult} and reject on
   * allowed=false / insufficient buying power / PDT BLOCKED / margin insufficient. A sentinel
   * {@code rejectReason="dispatch_failed:..."} -> {@link RejectionReason#PRE_TRADE_CHECK_FAILED}
   * mapping preserves fail-closed semantics when the workflow cannot reach the activity.
   *
   * <p>Buying-power comparison uses the same options notional formula as the notional-cap gate so
   * the values are apples-to-apples.
   */
  private RiskDecision checkPreTradeCheck(PortfolioContext ctx) {
    if (!Boolean.TRUE.equals(ctx.config.getPreTradeCheckEnabled())) {
      return null;
    }
    PreTradeCheckResult result = ctx.preTradeResult;
    if (result == null) {
      return RiskDecision.rejected(RejectionReason.PRE_TRADE_CHECK_FAILED, "null_result");
    }
    if (!Boolean.TRUE.equals(result.getAllowed())) {
      String reason = result.getRejectReason() == null ? "" : result.getRejectReason();
      return RiskDecision.rejected(
          RejectionReason.PRE_TRADE_CHECK_FAILED, "allowed=false reason=" + reason);
    }
    BigDecimal notional = ctx.entryNotional;
    BigDecimal buyingPower = result.getBuyingPower();
    if (buyingPower == null || buyingPower.compareTo(notional) < 0) {
      BigDecimal bp = buyingPower == null ? BigDecimal.ZERO : buyingPower;
      return RiskDecision.rejected(
          RejectionReason.PRE_TRADE_CHECK_FAILED,
          "buying_power=" + bp.toPlainString() + " required=" + notional.toPlainString());
    }
    if (result.getPdtStatus() == PreTradeCheckResult.PdtStatus.BLOCKED) {
      return RiskDecision.rejected(RejectionReason.PRE_TRADE_CHECK_FAILED, "pdt=BLOCKED");
    }
    if (!Boolean.TRUE.equals(result.getMarginSufficient())) {
      return RiskDecision.rejected(RejectionReason.PRE_TRADE_CHECK_FAILED, "margin=insufficient");
    }
    return null;
  }

  /** Uses {@code instanceof PermissiveDefaultPreTradeCheck} to detect the no-op default bean. */
  @Override
  public void assertPreTradeCheckRoutable(StrategyConfig config) {
    if (!Boolean.TRUE.equals(config.getPreTradeCheckEnabled())) {
      return;
    }
    if (preTradeCheckActivity instanceof PermissiveDefaultPreTradeCheck) {
      throw ApplicationFailure.newNonRetryableFailure(
          "pre_trade_check enabled for tenant="
              + config.getTenantId()
              + " strategy="
              + config.getStrategyId()
              + " but only the permissive default PreTradeCheckActivity bean is wired",
          "PreTradeCheckMisconfigured");
    }
  }

  private static BigDecimal sumOpenNotional(PortfolioContext ctx) {
    return ctx.openPositions().stream()
        .map(PortfolioSnapshot.OpenPosition::openNotional)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /**
   * Per-contract notional dollars: {@code price × contracts × CONTRACT_MULTIPLIER}. The 1-contract
   * floor is the responsibility of the caller — the risk gate runs before {@link
   * Sizing#computeContracts} so the entry-point methods pass {@code (limit, 1L)} as a conservative
   * under-estimate that keeps the gate from rejecting a borderline entry the workflow would have
   * sized down. Threading {@code contracts} explicitly future-proofs the helper if the gate later
   * switches to a sized count.
   */
  private static BigDecimal entryNotional(BigDecimal price, long contracts) {
    BigDecimal p = price == null ? BigDecimal.ZERO : price;
    return p.multiply(BigDecimal.valueOf(contracts)).multiply(Sizing.CONTRACT_MULTIPLIER);
  }

  /**
   * Issue #3: pick the per-side signal-age ceiling. BTO and AVG (treated as a buy-to-open variant
   * by the rest of the pipeline) use {@code max_signal_age_bto_secs}; STC uses {@code
   * max_signal_age_stc_secs}. The deprecated {@code max_signal_age_secs} is consulted only as a
   * last resort for back-compat with old fixtures/audit records. The schema's per-side fields are
   * required, so any value set in YAML is by definition an explicit per-strategy override; the
   * "explicit override above 120s" policy from Issue #3 is enforced at the configuration layer
   * (YAML review) rather than at runtime, because the schema cap of 3600s + the required field
   * already make any wide window visible in the diff.
   */
  private long resolveMaxSignalAgeSecs(CopytradeSignalPayload payload, StrategyConfig config) {
    Long perSide =
        payload.getAction() == CopytradeSignalPayload.Action.STC
            ? config.getMaxSignalAgeStcSecs()
            : config.getMaxSignalAgeBtoSecs();
    if (perSide != null) {
      return perSide;
    }
    // Back-compat: only reached if per-side fields are absent (older fixtures).
    Long legacy = config.getMaxSignalAgeSecs();
    if (legacy != null) {
      return legacy;
    }
    // Defensive: both unset is a config error; fall back to the documented BTO default
    // rather than NPE in the hot path.
    return 30L;
  }

  /**
   * Reads the kill-switch state and returns a rejection if tripped or within cool-down. Any
   * exception from the query path is treated as fail-closed with KILL_SWITCH_UNAVAILABLE — this
   * covers WorkflowNotFoundException, WorkflowQueryException, WorkflowQueryRejectedException,
   * WorkflowServiceException, and any TimeoutException wrapped in a RuntimeException.
   */
  @Override
  public RiskDecision checkKillSwitchHalt(String tenantId, String strategyId) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    RiskDecision strategyScope = checkKillSwitch(tenantId, strategyId, now);
    if (strategyScope != null) {
      return strategyScope;
    }
    return checkAccountKillSwitch(tenantId, now);
  }

  /**
   * Phase F4B: headroom contract count for the clamp-to-fit policy. Shares the SAME capital-base
   * math as {@link #checkNotionalCap} — {@code cap = capPct × (cash + sumOpenNotional)} — so the
   * clamp ceiling and the reject gate agree. The {@code openPositions()} seam fails closed by
   * propagation (no catch), matching the gate's #325 contract: a Visibility error must not yield a
   * permissive (large) headroom.
   *
   * <p>NOTE: this headroom read and the {@link #checkNotionalCap} gate make INDEPENDENT Visibility
   * reads of {@code openPositions()} — a benign TOCTOU window acceptable at this fidelity target.
   * Do not collapse them into one read without re-examining the gate's fail-closed failure modes.
   */
  @Override
  public long notionalCapHeadroomContracts(
      StrategyConfig config,
      BigDecimal limit,
      BigDecimal accountCash,
      String tenantId,
      String strategyId) {
    BigDecimal capPct;
    try {
      capPct = resolveNotionalCapPct(config);
    } catch (AmbiguousCapConfigException e) {
      // Ambiguous cap → no headroom (fail-closed); the gate independently rejects ambiguous
      // configs.
      return 0L;
    }
    if (capPct == null) {
      // Gate disabled → no notional-cap constraint; the workflow's MIN-composition no-ops.
      return Long.MAX_VALUE;
    }
    if (accountCash == null || accountCash.signum() <= 0) {
      // Fail-closed parity with checkNotionalCap's cash_unavailable reject: zero headroom.
      return 0L;
    }
    BigDecimal price = limit == null ? BigDecimal.ZERO : limit;
    BigDecimal pricePerContract = price.multiply(Sizing.CONTRACT_MULTIPLIER);
    if (pricePerContract.signum() <= 0) {
      return 0L;
    }
    // The entryNotional(price, 1L) arg is only here to carry `price` into the context shape;
    // sumOpenNotional reads only openPositions(), never entryNotional.
    PortfolioContext ctx =
        new PortfolioContext(
            tenantId, strategyId, null, config, null, entryNotional(price, 1L), accountCash);
    BigDecimal sumOpenNotional = sumOpenNotional(ctx);
    BigDecimal capitalBase = accountCash.add(sumOpenNotional);
    BigDecimal cap = capitalBase.multiply(capPct);
    BigDecimal remaining = cap.subtract(sumOpenNotional);
    if (remaining.signum() <= 0) {
      return 0L;
    }
    // Clamp to the unconstrained sentinel (Long.MAX_VALUE — the same value the cap-not-configured
    // path returns, which the workflow's MIN-composition treats as "no constraint"). A huge
    // headroom
    // (no open positions, large cash, very cheap option) can overflow long; longValueExact() would
    // throw an unexpected activity error instead of yielding the no-constraint sentinel.
    BigDecimal q = remaining.divide(pricePerContract, 0, RoundingMode.FLOOR);
    return q.compareTo(LONG_MAX_AS_DECIMAL) >= 0 ? Long.MAX_VALUE : q.longValueExact();
  }

  /**
   * Account-scope kill-switch read, keyed on {@code t-<tenant>/account/killswitch} via {@link
   * AccountKillSwitchWorkflow#killswitchState()} ({@code account_killswitch_state} query). This is
   * the scope the {@code auto:account_daily_loss} heartbeat trips — distinct from the per-strategy
   * {@link #checkKillSwitch} read, which never reflects an account-wide trip. Same fail-closed
   * semantics: any query failure or null state rejects with {@link
   * RejectionReason#KILL_SWITCH_UNAVAILABLE}.
   */
  private RiskDecision checkAccountKillSwitch(String tenantId, OffsetDateTime now) {
    if (workflowClient == null) {
      return killSwitchUnavailable("account", "no_client");
    }
    KillSwitchState state;
    try {
      String wfId = WorkflowIds.accountKillswitch(tenantId);
      AccountKillSwitchWorkflow stub =
          workflowClient.newWorkflowStub(AccountKillSwitchWorkflow.class, wfId);
      state = stub.killswitchState();
    } catch (Exception e) {
      return killSwitchUnavailable("account", e.getClass().getSimpleName());
    }
    if (state == null) {
      return killSwitchUnavailable("account", "null_state");
    }
    if (Boolean.TRUE.equals(state.getTripped())) {
      String detail = state.getReason() != null ? "reason=" + state.getReason() : null;
      return RiskDecision.rejected(RejectionReason.KILL_SWITCH_TRIPPED, detail);
    }
    OffsetDateTime cd = state.getCoolingDownUntil();
    if (cd != null && now.isBefore(cd)) {
      return RiskDecision.rejected(
          RejectionReason.KILL_SWITCH_COOLING_DOWN, "until=" + cd.toString());
    }
    return null;
  }

  private RiskDecision checkKillSwitch(String tenantId, String strategyId, OffsetDateTime now) {
    if (workflowClient == null) {
      // Defensive: production env always wires WorkflowClient; fail closed if it is somehow null.
      return killSwitchUnavailable("strategy", "no_client");
    }
    KillSwitchState state;
    try {
      String wfId = WorkflowIds.killswitch(tenantId, strategyId);
      KillSwitchWorkflow stub = workflowClient.newWorkflowStub(KillSwitchWorkflow.class, wfId);
      state = stub.killswitchState();
    } catch (Exception e) {
      return killSwitchUnavailable("strategy", e.getClass().getSimpleName());
    }
    if (state == null) {
      return killSwitchUnavailable("strategy", "null_state");
    }
    if (Boolean.TRUE.equals(state.getTripped())) {
      String detail = state.getReason() != null ? "reason=" + state.getReason() : null;
      return RiskDecision.rejected(RejectionReason.KILL_SWITCH_TRIPPED, detail);
    }
    OffsetDateTime cd = state.getCoolingDownUntil();
    if (cd != null && now.isBefore(cd)) {
      return RiskDecision.rejected(
          RejectionReason.KILL_SWITCH_COOLING_DOWN, "until=" + cd.toString());
    }
    return null;
  }

  /**
   * C2 fail-closed emitter shared by {@link #checkAccountKillSwitch} and {@link #checkKillSwitch}:
   * increments the tagged {@link #KILL_SWITCH_UNAVAILABLE_COUNTER_NAME} counter and returns the
   * {@link RejectionReason#KILL_SWITCH_UNAVAILABLE} decision with a {@code scope:reason} detail, so
   * the metric fires exactly where the scope-tagged detail is produced. Counters are cached per
   * {@code scope|reason} (bounded cardinality: reason is {@code no_client}/{@code null_state}/an
   * exception class name).
   */
  private RiskDecision killSwitchUnavailable(String scope, String reason) {
    Counter counter =
        killSwitchUnavailableCounters.computeIfAbsent(
            scope + "|" + reason,
            key ->
                Counter.builder(KILL_SWITCH_UNAVAILABLE_COUNTER_NAME)
                    .description(
                        "Kill-switch read fail-closed to KILL_SWITCH_UNAVAILABLE (C2); tagged by scope (account|strategy) and reason.")
                    .tag("scope", scope)
                    .tag("reason", reason)
                    .register(meterRegistry));
    counter.increment();
    return RiskDecision.rejected(RejectionReason.KILL_SWITCH_UNAVAILABLE, scope + ":" + reason);
  }
}
