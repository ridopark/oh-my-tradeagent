package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.LivePromotionApprovalRequest;
import io.temporal.activity.ActivityInterface;

/**
 * Phase 7 prep (issue #87) — dual-control sign-off recording for live-broker promotion.
 *
 * <p>Single Activity emits one {@code LivePromotionApproved} audit event via {@link
 * AuditActivities#log} once approver IDs pass the validator. Validation rejects same-ID or blank
 * dual-control requests with {@code IllegalArgumentException("approvers_must_differ")}; the audit
 * event is emitted only after validation passes. The actual {@code broker_target} ConfigMap flip is
 * operator-driven post-sign-off (see {@code docs/ops/live-promotion-rollback.md §Sign-off
 * recording}); this Activity is verification-only.
 */
@ActivityInterface
public interface LivePromotionActivities {

  void approve(LivePromotionApprovalRequest request);
}
