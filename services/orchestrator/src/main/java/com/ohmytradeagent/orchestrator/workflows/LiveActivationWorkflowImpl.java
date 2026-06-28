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
import com.ohmytradeagent.orchestrator.bootstrap.StrategyConfigInvariants;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.math.BigDecimal;
import java.time.Duration;

/**
 * Phase F (operator-account-onboarding) impl of the one-click live activation / deactivation
 * carriers. Net-new workflow type started fresh per call — NO {@code Workflow.getVersion}
 * change-point. Determinism: all inputs are the workflow request; no clock/random reads (the
 * approval timestamp is stamped inside {@code LivePromotionActivities.activate}, an Activity).
 *
 * <p><b>activateLive</b> runs the fail-closed gate in order, each step its own refusal reason:
 *
 * <ol>
 *   <li>read config (in-process {@code StrategyActivities.get}); not live → {@code
 *       REJECTED_NOT_LIVE}.
 *   <li>{@code StrategyConfigInvariants.validateLiveRequiredGates} (daily_loss_threshold &gt; 0 +
 *       notional cap set); {@code IllegalStateException} → {@code REJECTED_CONFIG}.
 *   <li>{@code capital_source == account_cash} → else {@code REJECTED_CAPITAL_SOURCE} (checked
 *       HERE, NOT inside the byte-stable {@code StrategyConfigInvariants}).
 *   <li>kill switch armable ({@code LiveActivationGateActivities.killSwitchArmable}); not → {@code
 *       REJECTED_KILLSWITCH}.
 *   <li>fresh account probe ({@code AccountSnapshotActivity} on {@code broker-<target>}); blank
 *       account_number or cash &le; 0 → {@code REJECTED_ACCOUNT}. Captures account_number as {@code
 *       expected_account_id} and the {@code trading_blocked} (403) flag — but NEVER refuses on 403
 *       (the broker-side block is the operator's intended throttle).
 *   <li>all pass → {@code LivePromotionActivities.activate} → {@code ACTIVATED}.
 * </ol>
 *
 * <p><b>deactivateLive</b> emits the {@code LivePromotionDeactivated} row AND trips the kill
 * switch, then returns {@code DEACTIVATED}.
 */
public class LiveActivationWorkflowImpl
    implements LiveActivationWorkflow, LiveDeactivationWorkflow {

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

  @Override
  public LiveActivationResult activateLive(LiveActivationRequest request) {
    String tenant = request.getTenantId();
    String strategyId = request.getStrategyId();
    String label = tenant + "/" + strategyId;

    // (a) stored config must be live.
    StrategyConfig config = strategy.get(tenant, strategyId);
    if (config == null || !StrategyConfigInvariants.isLive(config)) {
      return result(LiveActivationResult.Outcome.REJECTED_NOT_LIVE, "strategy is not live", null);
    }

    // (b) required live loss gates (daily_loss_threshold > 0 + notional cap set). The invariant
    // throws IllegalStateException; coarsen it into a refusal carrying its message.
    try {
      StrategyConfigInvariants.validateLiveRequiredGates(config, label);
    } catch (IllegalStateException e) {
      return result(LiveActivationResult.Outcome.REJECTED_CONFIG, e.getMessage(), null);
    }

    // (c) capital_source must be account_cash (NOT the static $100k global base). Checked HERE so
    // StrategyConfigInvariants stays byte-stable.
    if (config.getCapitalSource() != StrategyConfig.CapitalSource.ACCOUNT_CASH) {
      return result(
          LiveActivationResult.Outcome.REJECTED_CAPITAL_SOURCE,
          "capital_source must be account_cash for live activation",
          null);
    }

    // (d) kill switch must be armable (running + queryable). Fail closed on unreachable.
    if (!gate.killSwitchArmable(tenant, strategyId)) {
      return result(
          LiveActivationResult.Outcome.REJECTED_KILLSWITCH, "kill switch not armable", null);
    }

    // (e) fresh account probe on broker-<target>. Blank account_number or non-positive cash refuses
    // (REJECTED_ACCOUNT). A 403-blocked account (trading_blocked) is recorded but NEVER refused.
    AccountSnapshotResult snapshot = probeAccount(request, config);
    String accountNumber = snapshot == null ? null : snapshot.getAccountNumber();
    BigDecimal cash = snapshot == null ? null : snapshot.getCash();
    if (accountNumber == null || accountNumber.isBlank() || cash == null || cash.signum() <= 0) {
      return result(
          LiveActivationResult.Outcome.REJECTED_ACCOUNT,
          "account probe returned no account or non-positive cash",
          null);
    }
    boolean broker403Blocked = snapshot.getTradingBlocked() != null && snapshot.getTradingBlocked();

    // (f) all gates passed — emit the fresh LivePromotionApproved row with the real operator + the
    // probed expected_account_id. The activate Activity stamps the on-chain approved_at.
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

    LiveActivationResult ok = result(LiveActivationResult.Outcome.ACTIVATED, null, accountNumber);
    ok.setBroker403Blocked(broker403Blocked);
    return ok;
  }

  @Override
  public LiveActivationResult deactivateLive(LiveDeactivationRequest request) {
    String tenant = request.getTenantId();
    String strategyId = request.getStrategyId();

    // Resolve the authoritative broker_target from the stored config so the
    // LivePromotionDeactivated
    // row carries the SAME broker_target the matched LivePromotionApproved row used — the gate's
    // deactivation probe matches subject->>'broker_target', so a placeholder would never void the
    // approval. The inbound request's broker_target is only a routing placeholder.
    StrategyConfig config = strategy.get(tenant, strategyId);
    LiveDeactivationRequest deactReq = new LiveDeactivationRequest();
    deactReq.setSchemaVersion(request.getSchemaVersion());
    deactReq.setTenantId(tenant);
    deactReq.setStrategyId(strategyId);
    deactReq.setOperatorId(request.getOperatorId());
    deactReq.setBrokerTarget(
        config != null && config.getBrokerTarget() != null
            ? LiveDeactivationRequest.BrokerTarget.fromValue(config.getBrokerTarget().value())
            : request.getBrokerTarget());

    // Emit the deactivation row FIRST (the durable gate-invalidating record), then trip the kill
    // switch as belt-and-suspenders so no live order can slip through before the next gate read.
    promotion.deactivate(deactReq);
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
