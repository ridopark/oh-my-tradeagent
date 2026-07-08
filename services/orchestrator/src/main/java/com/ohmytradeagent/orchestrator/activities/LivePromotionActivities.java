package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.LiveActivationRequest;
import com.ohmytradeagent.contract.LiveDeactivationRequest;
import io.temporal.activity.ActivityInterface;

/**
 * Phase F (operator-account-onboarding) single-operator one-click activate / deactivate writes for
 * live-broker promotion.
 *
 * <p>{@link #activate} emits one gate-readable {@code LivePromotionApproved} kind via the on-chain
 * {@link AuditActivities#log} path for a SINGLE authenticated operator (no two-approver
 * requirement) — the gate-validity checks (live config, loss gates, capital_source, kill-switch,
 * fresh account probe) are performed UPSTREAM by {@code LiveActivationWorkflow}, not here. {@link
 * #deactivate} emits a {@code LivePromotionDeactivated} row that invalidates a prior approval (the
 * order-time gate then fails closed). The actual {@code broker_target} ConfigMap flip remains
 * operator-driven post-sign-off; these Activities are verification-record-only.
 */
@ActivityInterface
public interface LivePromotionActivities {

  /**
   * Phase F: emit one gate-readable {@code LivePromotionApproved} row attributed to a single
   * authenticated operator (NOT dual-control). Subject carries {@code operator_id}, {@code
   * expected_account_id}, and {@code activation_mode="one_click"}; ZERO key material.
   */
  void activate(LiveActivationRequest request);

  /**
   * Phase F: emit one {@code LivePromotionDeactivated} row for {@code (tenant, strategy,
   * broker_target)}. A row whose {@code occurred_at} is strictly after the matched approval voids
   * the live promotion at the order-time gate.
   */
  void deactivate(LiveDeactivationRequest request);
}
