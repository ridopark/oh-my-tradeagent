package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.PreTradeCheckRequest;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.domain.RejectionReason;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflow;
import io.temporal.client.WorkflowClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
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

  /** Standard option contract multiplier (premium dollars per contract = price * 100). */
  static final BigDecimal OPTIONS_CONTRACT_MULTIPLIER = new BigDecimal("100");

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
        noOpPortfolioSnapshot(),
        SectorResolver.CONFIG_BACKED,
        noOpDailyTradeCounter(),
        noOpDrawdownSampler(),
        noOpPreTradeCheck());
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
  public RiskDecision checkEntry(CopytradeSignalPayload payload, StrategyConfig config) {
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
    // the order below mirrors the recommendation list in issue #6.
    RiskDecision portfolioDecision = checkPortfolioGates(payload, config);
    if (portfolioDecision != null) {
      return portfolioDecision;
    }

    return RiskDecision.approved();
  }

  private RiskDecision checkPortfolioGates(CopytradeSignalPayload payload, StrategyConfig config) {
    RiskDecision d;

    d = checkNotionalCap(payload, config);
    if (d != null) {
      return d;
    }

    d = checkSameUnderlyingCount(payload, config);
    if (d != null) {
      return d;
    }

    d = checkSectorConcentration(payload, config);
    if (d != null) {
      return d;
    }

    d = checkDailyTradeCount(payload, config);
    if (d != null) {
      return d;
    }

    d = checkDrawdownVelocity(payload, config);
    if (d != null) {
      return d;
    }

    d = checkPreTradeCheck(payload, config);
    if (d != null) {
      return d;
    }
    return null;
  }

  /**
   * notional_cap_pct_of_equity: reject when (sum_open_notional + new_notional) > cap_pct * equity.
   * New entry notional uses the same options multiplier as Sizing (qty * price * 100). Equity == 0
   * fails closed so a missing/unavailable equity source can't accidentally pass an unbounded cap.
   */
  private RiskDecision checkNotionalCap(CopytradeSignalPayload payload, StrategyConfig config) {
    BigDecimal capPct = config.getNotionalCapPctOfEquity();
    if (capPct == null) {
      return null;
    }
    BigDecimal equity =
        portfolioSnapshot.accountEquity(payload.getTenantId(), payload.getStrategyId());
    if (equity == null || equity.signum() <= 0) {
      return RiskDecision.rejected(RejectionReason.NOTIONAL_CAP_EXCEEDED, "equity_unavailable");
    }
    BigDecimal cap = equity.multiply(capPct);
    BigDecimal openSum = sumOpenNotional(payload);
    BigDecimal newNotional = entryNotional(payload);
    BigDecimal projected = openSum.add(newNotional);
    if (projected.compareTo(cap) > 0) {
      return RiskDecision.rejected(
          RejectionReason.NOTIONAL_CAP_EXCEEDED,
          "notional=" + stripTrailingZeros(projected) + " cap=" + stripTrailingZeros(cap));
    }
    return null;
  }

  private RiskDecision checkSameUnderlyingCount(
      CopytradeSignalPayload payload, StrategyConfig config) {
    Long cap = config.getSameUnderlyingCount();
    if (cap == null) {
      return null;
    }
    String ticker = payload.getTicker();
    long matching =
        openPositionsFor(payload).stream()
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
  private RiskDecision checkSectorConcentration(
      CopytradeSignalPayload payload, StrategyConfig config) {
    Long cap = config.getSectorConcentrationCap();
    if (cap == null) {
      return null;
    }
    String sector = sectorResolver.resolve(payload.getTicker(), config);
    if (SectorResolver.UNKNOWN_SECTOR.equals(sector)) {
      return null;
    }
    long matching =
        openPositionsFor(payload).stream()
            .map(p -> sectorResolver.resolve(p.underlyingTicker(), config))
            .filter(sector::equals)
            .count();
    if (matching >= cap) {
      return RiskDecision.rejected(
          RejectionReason.SECTOR_CONCENTRATION_EXCEEDED,
          "sector=" + sector + " count=" + matching + " max=" + cap);
    }
    return null;
  }

  private RiskDecision checkDailyTradeCount(CopytradeSignalPayload payload, StrategyConfig config) {
    Long cap = config.getDailyTradeCount();
    if (cap == null) {
      return null;
    }
    LocalDate today = OffsetDateTime.now(clock).toLocalDate();
    long count = dailyTradeCounter.count(payload.getTenantId(), payload.getStrategyId(), today);
    if (count >= cap) {
      return RiskDecision.rejected(
          RejectionReason.DAILY_TRADE_COUNT_EXCEEDED, "count=" + count + " max=" + cap);
    }
    return null;
  }

  private RiskDecision checkDrawdownVelocity(
      CopytradeSignalPayload payload, StrategyConfig config) {
    BigDecimal threshold = config.getDrawdownVelocityThreshold();
    if (threshold == null) {
      return null;
    }
    BigDecimal rate =
        drawdownVelocitySampler.sampleLossRatePerMinute(
            payload.getTenantId(), payload.getStrategyId());
    if (rate == null) {
      // Fail closed on a missing sample so a broken sampler can't quietly skip the gate.
      return RiskDecision.rejected(RejectionReason.DRAWDOWN_VELOCITY_EXCEEDED, "rate_unavailable");
    }
    if (rate.compareTo(threshold) >= 0) {
      return RiskDecision.rejected(
          RejectionReason.DRAWDOWN_VELOCITY_EXCEEDED,
          "rate=" + stripTrailingZeros(rate) + " max=" + stripTrailingZeros(threshold));
    }
    return null;
  }

  /**
   * pre_trade_check: delegate to exec-svc Activity, fail closed on any exception, and reject on
   * allowed=false / insufficient buying power / PDT BLOCKED / margin insufficient. Buying-power
   * comparison uses the same options notional formula as the notional-cap gate so the values are
   * apples-to-apples.
   */
  private RiskDecision checkPreTradeCheck(CopytradeSignalPayload payload, StrategyConfig config) {
    if (!Boolean.TRUE.equals(config.getPreTradeCheckEnabled())) {
      return null;
    }
    BigDecimal notional = entryNotional(payload);
    PreTradeCheckResult result;
    try {
      result = preTradeCheckActivity.preTradeCheck(toRequest(payload, config, notional));
    } catch (Exception e) {
      return RiskDecision.rejected(
          RejectionReason.PRE_TRADE_CHECK_FAILED, e.getClass().getSimpleName());
    }
    if (result == null) {
      return RiskDecision.rejected(RejectionReason.PRE_TRADE_CHECK_FAILED, "null_result");
    }
    if (!Boolean.TRUE.equals(result.getAllowed())) {
      String reason = result.getRejectReason() == null ? "" : result.getRejectReason();
      return RiskDecision.rejected(
          RejectionReason.PRE_TRADE_CHECK_FAILED, "allowed=false reason=" + reason);
    }
    BigDecimal buyingPower = result.getBuyingPower();
    if (buyingPower == null || buyingPower.compareTo(notional) < 0) {
      return RiskDecision.rejected(
          RejectionReason.PRE_TRADE_CHECK_FAILED,
          "buying_power="
              + stripTrailingZeros(buyingPower == null ? BigDecimal.ZERO : buyingPower)
              + " required="
              + stripTrailingZeros(notional));
    }
    if (result.getPdtStatus() == PreTradeCheckResult.PdtStatus.BLOCKED) {
      return RiskDecision.rejected(RejectionReason.PRE_TRADE_CHECK_FAILED, "pdt=BLOCKED");
    }
    if (!Boolean.TRUE.equals(result.getMarginSufficient())) {
      return RiskDecision.rejected(RejectionReason.PRE_TRADE_CHECK_FAILED, "margin=insufficient");
    }
    return null;
  }

  private PreTradeCheckRequest toRequest(
      CopytradeSignalPayload payload, StrategyConfig config, BigDecimal notional) {
    PreTradeCheckRequest r = new PreTradeCheckRequest();
    r.setSchemaVersion(1L);
    r.setTenantId(payload.getTenantId());
    r.setStrategyId(payload.getStrategyId());
    r.setBrokerTarget(
        PreTradeCheckRequest.BrokerTarget.fromValue(config.getBrokerTarget().value()));
    // OCC symbol resolution happens later in the workflow; for the pre-trade gate we send the
    // ticker prefix that broker adapters can resolve themselves. Field shape is stable for the
    // future-state once OCC is resolved before the gate.
    r.setOptionSymbol(payload.getTicker());
    r.setSide(PreTradeCheckRequest.Side.BUY);
    r.setQty(Math.max(1L, sizeOneContractFallback(config)));
    r.setEstimatedNotional(notional);
    r.setCorrelationId(payload.getSignalId());
    return r;
  }

  /**
   * Pre-resolve sizing pulls in a multi-input dependency we don't have here; for the gate we use
   * {@code min_contracts} as the lower bound and let the broker decide whether the requested side
   * fits. This matches the {@code Sizing.computeContracts} clamp floor.
   */
  private long sizeOneContractFallback(StrategyConfig config) {
    return config.getMinContracts() == null ? 1L : config.getMinContracts();
  }

  private BigDecimal sumOpenNotional(CopytradeSignalPayload payload) {
    return openPositionsFor(payload).stream()
        .map(PortfolioSnapshot.OpenPosition::openNotional)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private List<PortfolioSnapshot.OpenPosition> openPositionsFor(CopytradeSignalPayload payload) {
    List<PortfolioSnapshot.OpenPosition> positions =
        portfolioSnapshot.openPositions(payload.getTenantId(), payload.getStrategyId());
    return positions == null ? List.of() : positions;
  }

  private BigDecimal entryNotional(CopytradeSignalPayload payload) {
    BigDecimal price = payload.getPrice() == null ? BigDecimal.ZERO : payload.getPrice();
    // Per-contract premium dollars. The risk gate runs before final qty is computed by the
    // workflow's Sizing.computeContracts, so we use a 1-contract floor (matches min_contracts
    // clamp in Sizing). This is a conservative under-estimate that keeps the gate from rejecting
    // a borderline entry the workflow would have sized down; the gate is meant to catch run-away
    // notional, not the single-contract baseline.
    return price.multiply(OPTIONS_CONTRACT_MULTIPLIER);
  }

  private static String stripTrailingZeros(BigDecimal v) {
    if (v == null) {
      return "0";
    }
    // setScale guards against `0E-8` style scientific output from BigDecimal arithmetic on
    // values that strip to exact zero (BigDecimal.ZERO.stripTrailingZeros().toPlainString() →
    // "0", but `new BigDecimal("0.0000").stripTrailingZeros().toPlainString()` → "0E-4").
    BigDecimal s = v.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
    if (s.scale() < 0) {
      s = s.setScale(0, RoundingMode.UNNECESSARY);
    }
    return s.toPlainString();
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

  // ----- no-op factories for the back-compat 3-arg constructor -----

  private static PortfolioSnapshot noOpPortfolioSnapshot() {
    return new PortfolioSnapshot() {
      @Override
      public List<OpenPosition> openPositions(String tenantId, String strategyId) {
        return List.of();
      }

      @Override
      public BigDecimal accountEquity(String tenantId, String strategyId) {
        return BigDecimal.ZERO;
      }
    };
  }

  private static DailyTradeCounter noOpDailyTradeCounter() {
    return (tenant, strategy, day) -> 0L;
  }

  private static DrawdownVelocitySampler noOpDrawdownSampler() {
    return (tenant, strategy) -> BigDecimal.ZERO;
  }

  private static PreTradeCheckActivity noOpPreTradeCheck() {
    return req -> {
      PreTradeCheckResult r = new PreTradeCheckResult();
      r.setSchemaVersion(1L);
      r.setAllowed(true);
      r.setBuyingPower(new BigDecimal("1000000000"));
      r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
      r.setMarginSufficient(true);
      return r;
    };
  }
}
