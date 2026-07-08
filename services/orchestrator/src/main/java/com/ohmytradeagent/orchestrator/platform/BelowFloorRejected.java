package com.ohmytradeagent.orchestrator.platform;

/**
 * Thrown by {@link TenantConfigWriter} when a tighten of the account-level daily-loss cap is a
 * valid reduction but would set the cap BELOW the policy floor ({@link
 * TenantConfigWriter#MIN_ACCOUNT_DAILY_LOSS_PCT} / {@link
 * TenantConfigWriter#MIN_ACCOUNT_DAILY_LOSS_THRESHOLD_USD}).
 *
 * <p>Risk-manager sign-off condition (C2): the {@code (0,1]} + forbid-0 range guard does NOT cover
 * near-zero, and a near-zero cap is IRREVERSIBLE tenant-side (raising it back is rejected by the
 * tighten-only rule) — it would force a near-instant forced-flatten and let a tenant brick their
 * own real-money account. Distinct from {@link DangerousFieldChangeRejected} (a raise/remove/add)
 * and {@link InvalidConfigException} (a malformed value) so the api-gateway maps it to its own
 * status. Nothing is persisted when this is thrown.
 */
public class BelowFloorRejected extends RuntimeException {
  public BelowFloorRejected(String message) {
    super(message);
  }
}
