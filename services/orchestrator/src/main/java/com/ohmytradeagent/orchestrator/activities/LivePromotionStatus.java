package com.ohmytradeagent.orchestrator.activities;

/**
 * P3-a (multi-tenant-broker-credentials): the result of the live-promotion safety gate verify
 * performed by {@link AuditQueryActivities#checkLivePromotion}. A plain Java enum serialized across
 * the Temporal activity boundary (SDK DataConverter) — deliberately NOT a JSON-Schema DTO, so it
 * needs no contract regen.
 *
 * <p>Only {@link #VALID} permits a live BTO to dispatch. Every other value is a fail-CLOSED
 * refusal:
 *
 * <ul>
 *   <li>{@link #VALID} — a fresh (not-stale) {@code LivePromotionApproved} row exists for the
 *       {@code (tenant_id, strategy_id, broker_target)} triple.
 *   <li>{@link #ABSENT} — no {@code LivePromotionApproved} row matches the triple.
 *   <li>{@link #STALE} — the most-recent matching approval is older than the staleness window.
 *   <li>{@link #CONFIG_CHANGED} — a risk-relevant {@code TenantConfigChanged} occurred AFTER the
 *       approval → re-approval required.
 *   <li>{@link #VERIFY_ERROR} — the verify itself could not run (no DB handle, or the read threw).
 *       Fail-closed: a verify failure must refuse a live order, never let an unapproved one
 *       through.
 * </ul>
 */
public enum LivePromotionStatus {
  VALID,
  ABSENT,
  STALE,
  CONFIG_CHANGED,
  VERIFY_ERROR
}
