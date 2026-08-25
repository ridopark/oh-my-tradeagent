package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AccountSnapshotRequest;
import com.ohmytradeagent.contract.AccountSnapshotResult;
import com.ohmytradeagent.contract.LiveActivationRequest;
import com.ohmytradeagent.contract.LiveActivationResult;
import com.ohmytradeagent.contract.LiveDeactivationRequest;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.AccountSnapshotActivity;
import com.ohmytradeagent.orchestrator.activities.LiveActivationGateActivities;
import com.ohmytradeagent.orchestrator.activities.LivePromotionActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import com.ohmytradeagent.orchestrator.activities.TenantConfigActivities;
import com.ohmytradeagent.orchestrator.bootstrap.StrategyConfigInvariants;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.math.BigDecimal;
import java.time.Duration;

/**
 * Phase F (operator-account-onboarding) impl of the one-click live activation / deactivation
 * carriers. Net-new workflow type started fresh per call. Determinism: all inputs are the workflow
 * request; no clock/random reads (the approval timestamp is stamped inside {@code
 * LivePromotionActivities.activate}, an Activity).
 *
 * <p><b>Replay note (kill-switch reset step):</b> the {@code gate.resetKillSwitch} Activity command
 * added at the END of {@code activateLive} needs NO {@code getVersion} gate. These runs are
 * ephemeral (seconds, one per operator click); the new command is strictly AFTER the last command
 * any in-flight history can contain, so a worker upgraded mid-run only makes forward progress
 * (issues the reset after replaying the recorded prefix) — it can never diverge from a recorded
 * command. This matches the class's net-new-per-call contract.
 *
 * <p><b>Replay note (Phase 3b account-cap-aware step (b)):</b> unlike the reset (a trailing
 * command), step (b) reads the tenant account cap via TWO {@code TenantConfigActivities} commands
 * inserted BEFORE existing commands ({@code gate.killSwitchArmable}, the probe) — that changes
 * command ordering for an in-flight history, so it IS version-gated ({@code
 * live-activation-account-cap-aware-v1}) even though runs are ephemeral. At {@code DEFAULT_VERSION}
 * the pre-3b 2-arg gate runs and issues no new command (recorded histories replay byte-identically,
 * proven by {@code LiveActivationWorkflowImplLegacyReplayTest}); at {@code v>=1} the two Activities
 * run and the 4-arg overload treats {@code daily_loss_threshold} as optional when the account cap
 * is armed.
 *
 * <p><b>activateLive</b> runs the fail-closed gate in order, each step its own refusal reason:
 *
 * <ol>
 *   <li>read config (in-process {@code StrategyActivities.get}); not live → {@code
 *       REJECTED_NOT_LIVE}.
 *   <li>{@code StrategyConfigInvariants.validateLiveRequiredGates}; {@code IllegalStateException} →
 *       {@code REJECTED_CONFIG}. Phase 3b + version gate {@code
 *       live-activation-account-cap-aware-v1}: at {@code v>=1} the tenant account cap is read via
 *       {@code TenantConfigActivities} and the 4-arg overload is used, so an armed account cap
 *       satisfies the invariant (per-strategy daily_loss_threshold optional) — a {@code -live}
 *       tenant with NO armed cap is still {@code REJECTED_CONFIG}. At {@code DEFAULT_VERSION} the
 *       pre-3b 2-arg gate (daily_loss_threshold &gt; 0 + notional cap set) runs unchanged.
 *   <li>{@code capital_source} ∈ {{@code account_cash}, {@code account_equity} (#790)} → else
 *       {@code REJECTED_CAPITAL_SOURCE} (checked HERE, NOT inside the byte-stable {@code
 *       StrategyConfigInvariants}). #780 + version gate {@code live-activation-static-capital-v1}:
 *       at {@code v>=1} an explicit {@code static} is admitted and validated after step 5's probe
 *       instead — {@code base × capital_weight} must not exceed 15% of the probed equity (see
 *       {@link #VERSION_STATIC_CAPITAL}).
 *   <li>kill switch armable ({@code LiveActivationGateActivities.killSwitchArmable}); not → {@code
 *       REJECTED_KILLSWITCH}.
 *   <li>fresh account probe ({@code AccountSnapshotActivity} on {@code broker-<target>}); blank
 *       account_number or cash &le; 0 → {@code REJECTED_ACCOUNT}. Captures account_number as {@code
 *       expected_account_id}.
 *   <li>all pass → {@code LivePromotionActivities.activate}, then {@code
 *       LiveActivationGateActivities.resetKillSwitch} (single-operator untrip so a prior one-click
 *       deactivate's trip no longer keeps the strategy halted) → {@code ACTIVATED}.
 * </ol>
 *
 * <p>Order is deliberately activate-THEN-reset and must NOT be swapped: the reset is fail-closed
 * (if it fails the strategy stays halted), whereas a reset-before-promote would leave the
 * watchlist-entry path — which does NOT gate on {@code LivePromotionApproved} — silently armed on a
 * partial failure. The GREEN "Strategy activated live" Discord alert ({@code
 * LiveActivationAlerter}) fires off the {@code KillSwitchResetApproved(via=live_activation)} audit
 * row the reset writes, NOT off {@code LivePromotionApproved}: that row commits LAST and only when
 * the untrip actually succeeded, so a {@code resetKillSwitch} failure produces no false "activated"
 * message.
 *
 * <p><b>deactivateLive</b> emits the {@code LivePromotionDeactivated} row AND trips the kill
 * switch, then returns {@code DEACTIVATED}.
 */
public class LiveActivationWorkflowImpl
    implements LiveActivationWorkflow, LiveDeactivationWorkflow {

  /**
   * Phase 3b (single-account-loss-rule) version marker for the account-cap-aware step (b). At
   * {@code DEFAULT_VERSION} activateLive keeps the EXACT pre-3b behavior (2-arg {@code
   * validateLiveRequiredGates}, NO {@code TenantConfigActivities} command) so recorded histories
   * replay byte-identically; at {@code v>=1} it reads the tenant account cap via two Activities and
   * uses the 4-arg overload. Pinned by a name-stability test — a rename would silently re-version
   * in-flight executions.
   */
  static final String VERSION_ACCOUNT_CAP_AWARE = "live-activation-account-cap-aware-v1";

  /**
   * Issue #780 version marker for static-capital live activation. At {@code DEFAULT_VERSION} step
   * (c) keeps the pre-#780 behavior (any non-account_cash capital_source → {@code
   * REJECTED_CAPITAL_SOURCE}, NO extra command). At {@code v>=1}, {@code static} is admitted
   * through step (c) and instead validated AFTER the step (e) account probe: the pod-global static
   * base is read via the net-new {@code StrategyActivities.capitalForStrategy} command (appended
   * only on the static path — no pre-#780 history can carry commands past its step (c) rejection,
   * so ordering is unaffected) and the encoded allocation {@code base × capital_weight} must not
   * exceed {@link #MAX_STATIC_ALLOCATION_FRACTION_OF_EQUITY} of the probed net-liquidation equity.
   * This keeps the guard's original intent — a live tenant must not size off a static base that
   * dwarfs its real account — while permitting the equity-encoded weights of the #780 cutover
   * (docs/ops/copytrade-static-sizing-cutover.md). An absent capital_source takes the same path:
   * the schema defaults the field to {@code static}, so the payload round-trip materializes the
   * default. Pinned by a name-stability test — a rename would silently re-version in-flight
   * executions.
   */
  static final String VERSION_STATIC_CAPITAL = "live-activation-static-capital-v1";

  /**
   * Ceiling on {@code (static base × capital_weight) / probed equity} for a static-capital live
   * activation. The #780 target encoding is ~10% of equity per position; 15% leaves headroom for
   * intraday equity drift without re-admitting the oversizing hazard step (c) exists to stop.
   */
  static final BigDecimal MAX_STATIC_ALLOCATION_FRACTION_OF_EQUITY = new BigDecimal("0.15");

  private static final ActivityOptions CORE_OPTIONS =
      ActivityOptions.newBuilder()
          .setStartToCloseTimeout(Duration.ofSeconds(15))
          .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
          .build();

  private final StrategyActivities strategy =
      Workflow.newActivityStub(StrategyActivities.class, CORE_OPTIONS);
  private final LiveActivationGateActivities gate =
      Workflow.newActivityStub(LiveActivationGateActivities.class, CORE_OPTIONS);
  private final LivePromotionActivities promotion =
      Workflow.newActivityStub(LivePromotionActivities.class, CORE_OPTIONS);
  private final TenantConfigActivities tenantConfig =
      Workflow.newActivityStub(TenantConfigActivities.class, CORE_OPTIONS);

  @Override
  public LiveActivationResult activateLive(LiveActivationRequest request) {
    String tenant = request.getTenantId();
    String strategyId = request.getStrategyId();
    String label = tenant + "/" + strategyId;

    // Phase 3b version gate — read UNCONDITIONALLY at the top so the marker is recorded on every
    // path (including the early REJECTED_NOT_LIVE return). A legacy in-flight history carries no
    // marker → resolves to DEFAULT_VERSION on replay → the pre-3b 2-arg step (b) below, issuing NO
    // TenantConfigActivities command (byte-identical replay).
    int accountCapAwareVersion =
        Workflow.getVersion(VERSION_ACCOUNT_CAP_AWARE, Workflow.DEFAULT_VERSION, 1);
    // #780 static-capital marker — read unconditionally right after the cap-aware marker (marker
    // ORDER is part of the replay contract) so it is recorded on every path.
    int staticCapitalVersion =
        Workflow.getVersion(VERSION_STATIC_CAPITAL, Workflow.DEFAULT_VERSION, 1);

    // (a) stored config must be live.
    StrategyConfig config = strategy.get(tenant, strategyId);
    if (config == null || !StrategyConfigInvariants.isLive(config)) {
      return result(LiveActivationResult.Outcome.REJECTED_NOT_LIVE, "strategy is not live", null);
    }

    // (b) required live loss gates. At v>=1 (Phase 3b) read the tenant account cap via Activities
    // and use the 4-arg overload — an armed account cap satisfies the live loss-breaker invariant
    // (per-strategy daily_loss_threshold optional; a -live tenant with NO armed cap is still
    // rejected). At DEFAULT_VERSION keep the pre-3b 2-arg gate (daily_loss_threshold > 0 + notional
    // cap set) with NO Activity call, so recorded histories replay byte-identically.
    try {
      if (accountCapAwareVersion >= 1) {
        BigDecimal accountDailyLossPct = tenantConfig.accountDailyLossPct(tenant);
        BigDecimal accountDailyLossThreshold = tenantConfig.accountDailyLossThreshold(tenant);
        StrategyConfigInvariants.validateLiveRequiredGates(
            config, accountDailyLossPct, accountDailyLossThreshold, label);
      } else {
        StrategyConfigInvariants.validateLiveRequiredGates(config, label);
      }
    } catch (IllegalStateException e) {
      return result(LiveActivationResult.Outcome.REJECTED_CONFIG, e.getMessage(), null);
    }

    // (c) capital_source must be account_cash — or, at static-capital v>=1, static, whose encoded
    // allocation is validated against the probed equity AFTER step (e) below. An absent-in-DB
    // capital_source is indistinguishable from an explicit static here: the schema defaults the
    // field to "static", so the Temporal payload round-trip materializes the default — consistent
    // with sizing, which also treats null/absent as static.
    boolean staticCapital =
        staticCapitalVersion >= 1
            && config.getCapitalSource() == StrategyConfig.CapitalSource.STATIC;
    // #790: account_equity is a TRACKING source like account_cash — it sizes from the live
    // account, so it needs none of the static path's encoded-weight-vs-equity arithmetic and is
    // admitted directly. Pure predicate widening on an enum value no recorded history can carry
    // (it did not exist), so legacy replays are untouched and no version gate is needed — unlike
    // static (VERSION_STATIC_CAPITAL), which appends a net-new capitalForStrategy command.
    boolean trackingSource =
        config.getCapitalSource() == StrategyConfig.CapitalSource.ACCOUNT_CASH
            || config.getCapitalSource() == StrategyConfig.CapitalSource.ACCOUNT_EQUITY;
    if (!staticCapital && !trackingSource) {
      return result(
          LiveActivationResult.Outcome.REJECTED_CAPITAL_SOURCE,
          "capital_source must be account_cash, account_equity, or static for live activation",
          null);
    }

    // (d) kill switch must be armable.
    if (!gate.killSwitchArmable(tenant, strategyId)) {
      return result(
          LiveActivationResult.Outcome.REJECTED_KILLSWITCH, "kill switch not armable", null);
    }

    // (e) fresh account probe — blank account_number or a non-positive SIZING BASE refuses. The
    // funds check matches the field the source will actually size from: account_equity checks
    // net-liquidation EQUITY (a fully-invested margin account legitimately runs cash ~0/negative
    // with positive equity — exactly the account shape account_equity serves), every other source
    // keeps the pre-#790 cash check. Pure predicate branching on an enum value no recorded
    // history can carry — no version gate needed.
    AccountSnapshotResult snapshot = probeAccount(request, config);
    String accountNumber = snapshot == null ? null : snapshot.getAccountNumber();
    boolean equitySource = config.getCapitalSource() == StrategyConfig.CapitalSource.ACCOUNT_EQUITY;
    BigDecimal sizingBase =
        snapshot == null ? null : (equitySource ? snapshot.getEquity() : snapshot.getCash());
    if (accountNumber == null
        || accountNumber.isBlank()
        || sizingBase == null
        || sizingBase.signum() <= 0) {
      return result(
          LiveActivationResult.Outcome.REJECTED_ACCOUNT,
          "account probe returned no account or non-positive " + (equitySource ? "equity" : "cash"),
          null);
    }

    // (e-2) static-capital allocation check (#780, v>=1 only — staticCapital is always false at
    // DEFAULT_VERSION). Fail-closed on every missing input: the guard exists to stop a live tenant
    // sizing off a static base that dwarfs its real account, so "can't tell" means "refuse".
    if (staticCapital) {
      BigDecimal weight = config.getCapitalWeight();
      if (weight == null || weight.signum() <= 0) {
        return result(
            LiveActivationResult.Outcome.REJECTED_CAPITAL_SOURCE,
            "static capital_source requires a positive capital_weight",
            null);
      }
      BigDecimal equity = snapshot.getEquity();
      if (equity == null || equity.signum() <= 0) {
        return result(
            LiveActivationResult.Outcome.REJECTED_CAPITAL_SOURCE,
            "account probe returned no equity to validate static sizing against",
            null);
      }
      BigDecimal base = strategy.capitalForStrategy(tenant, strategyId);
      if (base == null || base.signum() <= 0) {
        return result(
            LiveActivationResult.Outcome.REJECTED_CAPITAL_SOURCE,
            "no positive static capital base configured",
            null);
      }
      BigDecimal allocation = base.multiply(weight);
      BigDecimal ceiling = equity.multiply(MAX_STATIC_ALLOCATION_FRACTION_OF_EQUITY);
      if (allocation.compareTo(ceiling) > 0) {
        return result(
            LiveActivationResult.Outcome.REJECTED_CAPITAL_SOURCE,
            "static allocation "
                + allocation.toPlainString()
                + " (base "
                + base.toPlainString()
                + " x weight "
                + weight.toPlainString()
                + ") exceeds "
                + MAX_STATIC_ALLOCATION_FRACTION_OF_EQUITY.toPlainString()
                + " of account equity "
                + equity.toPlainString()
                + " — reduce capital_weight",
            null);
      }
    }

    // (f) all gates passed — emit the fresh LivePromotionApproved row.
    //
    // broker_target is taken from the STORED config (the authoritative value the order-time gate
    // looks up: AuditQueryActivitiesImpl.checkLivePromotion matches subject->>'broker_target'
    // against config.getBrokerTarget().value()) — NOT from the inbound request, which carries only
    // a routing placeholder. Setting the request value would write a row the gate can never match.
    LiveActivationRequest activateReq = new LiveActivationRequest();
    activateReq.setSchemaVersion(request.getSchemaVersion());
    activateReq.setTenantId(tenant);
    activateReq.setStrategyId(strategyId);
    activateReq.setBrokerTarget(
        LiveActivationRequest.BrokerTarget.fromValue(config.getBrokerTarget().value()));
    activateReq.setOperatorId(request.getOperatorId());
    activateReq.setExpectedAccountId(accountNumber);
    promotion.activate(activateReq);

    // (g) UNTRIP the kill switch so the strategy actually resumes. A prior one-click deactivate
    // trips the switch (deactivateLive), which leaves risk.check_entry fail-closed on tripped==true
    // — so without this the fresh LivePromotionApproved row would be inert and the strategy would
    // stay HALTED. Single-operator reset (NOT dual control), keeping the existing 60s cooldown;
    // idempotent when the switch is not tripped (see LiveActivationGateActivities.resetKillSwitch).
    gate.resetKillSwitch(tenant, strategyId, request.getOperatorId());

    return result(LiveActivationResult.Outcome.ACTIVATED, null, accountNumber);
  }

  @Override
  public LiveActivationResult deactivateLive(LiveDeactivationRequest request) {
    String tenant = request.getTenantId();
    String strategyId = request.getStrategyId();

    // Emit the deactivation row ONLY when broker_target resolves from the STORED config, so it
    // matches the broker_target the gate's deactivation probe looks up (subject->>'broker_target').
    // The inbound request's broker_target is only a routing placeholder the gate could never match,
    // so on a config-miss we must NOT write an unmatchable (silently inert) void row that would
    // still report success — and with no config there is no live order path to void anyway. The
    // kill-switch trip below is the real stop (it also halts in-flight / open positions, which the
    // void row — which only brakes new entries — does not).
    StrategyConfig config = strategy.get(tenant, strategyId);
    if (config != null && config.getBrokerTarget() != null) {
      LiveDeactivationRequest deactReq = new LiveDeactivationRequest();
      deactReq.setSchemaVersion(request.getSchemaVersion());
      deactReq.setTenantId(tenant);
      deactReq.setStrategyId(strategyId);
      deactReq.setOperatorId(request.getOperatorId());
      deactReq.setBrokerTarget(
          LiveDeactivationRequest.BrokerTarget.fromValue(config.getBrokerTarget().value()));
      promotion.deactivate(deactReq);
    }
    gate.tripKillSwitch(tenant, strategyId, request.getOperatorId(), "live_deactivation:one_click");

    return result(LiveActivationResult.Outcome.DEACTIVATED, null, null);
  }

  /**
   * Dispatch the account probe to the {@code broker-<target>} queue (a task-queue-pinned stub can
   * only be created inside a workflow), mirroring {@code AccountSnapshotWorkflowImpl}. tenant_id is
   * populated so exec resolves the requesting tenant's broker.
   */
  private AccountSnapshotResult probeAccount(LiveActivationRequest request, StrategyConfig config) {
    String brokerTarget = config.getBrokerTarget().value();
    AccountSnapshotActivity activity =
        Workflow.newActivityStub(
            AccountSnapshotActivity.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(ExecActivitiesFactory.taskQueueFor(brokerTarget))
                .setStartToCloseTimeout(Duration.ofSeconds(15))
                .setScheduleToCloseTimeout(Duration.ofSeconds(60))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                .build());

    AccountSnapshotRequest r = new AccountSnapshotRequest();
    r.setSchemaVersion(1L);
    r.setBrokerTarget(AccountSnapshotRequest.BrokerTarget.fromValue(brokerTarget));
    r.setTenantId(request.getTenantId());
    return activity.accountSnapshot(r);
  }

  private static LiveActivationResult result(
      LiveActivationResult.Outcome outcome, String reason, String expectedAccountId) {
    LiveActivationResult r = new LiveActivationResult();
    r.setSchemaVersion(1L);
    r.setOutcome(outcome);
    if (reason != null) {
      r.setReason(reason);
    }
    if (expectedAccountId != null) {
      r.setExpectedAccountId(expectedAccountId);
    }
    return r;
  }
}
