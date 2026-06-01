package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.domain.RejectionReason;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import com.ohmytradeagent.orchestrator.domain.Sizing;
import com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.failure.ApplicationFailure;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Risk gate Activity. Phase 5 wires the kill-switch read via {@code
 * KillSwitchWorkflow.killswitchState()} — any failure (workflow-not-found, query rejection, service
 * timeout) fails CLOSED with {@link RejectionReason#KILL_SWITCH_UNAVAILABLE}. Tripped or
 * within-cooldown state rejects with the corresponding reason.
 *
 * <p>Issue #6 adds six portfolio-level sub-gates that run after the existing per-order gates:
 * {@code notional_cap_pct_of_equity}, {@code same_underlying_count}, {@code
 * sector_concentration_cap}, {@code daily_trade_count}, {@code drawdown_velocity_threshold}, and
 * {@code pre_trade_check}. Each is strictly opt-in via the corresponding {@code StrategyConfig}
 * field; null/absent config short-circuits the gate so existing strategies remain unchanged.
 */
@Component
public class RiskActivitiesImpl implements RiskActivities {

  static final Duration FUTURE_DATE_TOLERANCE = Duration.ofSeconds(5);

  private final PositionCounter positionCounter;
  private final Clock clock;
  private final WorkflowClient workflowClient;
  private final PortfolioSnapshot portfolioSnapshot;
  private final SectorResolver sectorResolver;
  private final DailyTradeCounter dailyTradeCounter;
  private final DrawdownVelocitySampler drawdownVelocitySampler;
  private final PreTradeCheckActivity preTradeCheckActivity;

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

  @Autowired
  public RiskActivitiesImpl(
      PositionCounter positionCounter,
      Clock clock,
      WorkflowClient workflowClient,
      PortfolioSnapshot portfolioSnapshot,
      SectorResolver sectorResolver,
      DailyTradeCounter dailyTradeCounter,
      DrawdownVelocitySampler drawdownVelocitySampler,
      PreTradeCheckActivity preTradeCheckActivity) {
    this.positionCounter = positionCounter;
    this.clock = clock;
    this.workflowClient = workflowClient;
    this.portfolioSnapshot = portfolioSnapshot;
    this.sectorResolver = sectorResolver;
    this.dailyTradeCounter = dailyTradeCounter;
    this.drawdownVelocitySampler = drawdownVelocitySampler;
    this.preTradeCheckActivity = preTradeCheckActivity;
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

    RiskDecision killSwitchDecision = checkKillSwitch(payload, now);
    if (killSwitchDecision != null) {
      return killSwitchDecision;
    }

    long openPositions = positionCounter.countOpen(payload.getTenantId(), payload.getStrategyId());
    if (openPositions >= config.getMaxPositions()) {
      return RiskDecision.rejected(RejectionReason.MAX_POSITIONS_EXCEEDED, "open=" + openPositions);
    }

    // Issue #6 portfolio-level gates. Each is config-gated so a strategy that opts out keeps the
    // pre-Issue-#6 behavior. Order matters only for which reason wins when multiple gates fail;
    // the order below mirrors the recommendation list in issue #6. Positions are fetched once and
    // shared across the gates that need them (production impl is a Temporal Visibility query).
    PortfolioContext ctx =
        new PortfolioContext(payload, config, preTradeResult, entryNotional, accountCash);
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

  /**
   * Per-call cache for the portfolio gates. Holds the open-position list (fetched lazily so a
   * deployment that disables all position-aware gates skips the Visibility query entirely) plus the
   * resolved entry notional, which several gates need. The {@code entryNotional} is pre-computed by
   * the entry-point method ({@link #checkEntry} = mirror price, {@link #checkEntryWithLimit} =
   * slip-adjusted limit) so the gate bodies stay source-agnostic.
   */
  private final class PortfolioContext {
    final CopytradeSignalPayload payload;
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
        CopytradeSignalPayload payload,
        StrategyConfig config,
        PreTradeCheckResult preTradeResult,
        BigDecimal entryNotional,
        BigDecimal accountCash) {
      this.payload = payload;
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
            portfolioSnapshot.openPositions(payload.getTenantId(), payload.getStrategyId());
        openPositions = positions == null ? List.of() : positions;
      }
      return openPositions;
    }
  }

  /**
   * notional_cap_pct_of_equity: reject when {@code (sum_open_notional + new_notional) > cap_pct *
   * (cash + sum_open_notional)}.
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
   * {@code equity_unavailable} rather than passing an unbounded cap — preserving the #317
   * fail-closed-on-zero contract. The capital base is zero only when BOTH cash is zero AND there
   * are no open positions, which is itself a reject (cannot size against a zero base).
   */
  private RiskDecision checkNotionalCap(PortfolioContext ctx) {
    BigDecimal capPct = ctx.config.getNotionalCapPctOfEquity();
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
      return RiskDecision.rejected(RejectionReason.NOTIONAL_CAP_EXCEEDED, "equity_unavailable");
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

  private RiskDecision checkSameUnderlyingCount(PortfolioContext ctx) {
    Long cap = ctx.config.getSameUnderlyingCount();
    if (cap == null) {
      return null;
    }
    String ticker = ctx.payload.getTicker();
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
    String sector = sectorResolver.resolve(ctx.payload.getTicker(), ctx.config);
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
    long count =
        dailyTradeCounter.count(ctx.payload.getTenantId(), ctx.payload.getStrategyId(), today);
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
    BigDecimal rate =
        drawdownVelocitySampler.sampleLossRatePerMinute(
            ctx.payload.getTenantId(), ctx.payload.getStrategyId());
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
  private RiskDecision checkKillSwitch(CopytradeSignalPayload payload, OffsetDateTime now) {
    if (workflowClient == null) {
      // Defensive: production env always wires WorkflowClient; fail closed if it is somehow null.
      return RiskDecision.rejected(RejectionReason.KILL_SWITCH_UNAVAILABLE, "no_client");
    }
    KillSwitchState state;
    try {
      String wfId = WorkflowIds.killswitch(payload.getTenantId(), payload.getStrategyId());
      KillSwitchWorkflow stub = workflowClient.newWorkflowStub(KillSwitchWorkflow.class, wfId);
      state = stub.killswitchState();
    } catch (Exception e) {
      return RiskDecision.rejected(
          RejectionReason.KILL_SWITCH_UNAVAILABLE, e.getClass().getSimpleName());
    }
    if (state == null) {
      return RiskDecision.rejected(RejectionReason.KILL_SWITCH_UNAVAILABLE, "null_state");
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
}
