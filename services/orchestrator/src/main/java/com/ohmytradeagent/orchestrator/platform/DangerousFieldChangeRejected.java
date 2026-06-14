package com.ohmytradeagent.orchestrator.platform;

/**
 * Thrown by the P0c-a config write path when a runtime write would increase or remove risk. A
 * runtime write may only reduce-or-hold risk because {@code KillSwitchWorkflowImpl.heartbeat()}
 * re-reads {@code daily_loss_threshold} + {@code notional_cap_pct_of_capital_base} from the DB
 * every tick — mutating those at runtime would disarm the live loss circuit-breaker. This guards
 * three field classes against the currently-stored row:
 *
 * <ul>
 *   <li>IDENTITY ({@code tenant_id}, {@code strategy_id}, {@code schema_version}) — must equal
 *       stored.
 *   <li>DANGEROUS ({@code broker_target}, {@code daily_loss_threshold}, {@code
 *       notional_cap_pct_of_capital_base}) — must equal stored; changing them is deferred to P3
 *       dual-control.
 *   <li>EXPOSURE ({@code max_contracts}, {@code min_contracts}, {@code max_positions}, {@code
 *       capital_weight}, {@code max_notional_per_signal}, {@code max_daily_notional_deployed}) —
 *       must not increase vs stored.
 * </ul>
 *
 * Nothing is persisted when this is thrown.
 */
public class DangerousFieldChangeRejected extends RuntimeException {
  public DangerousFieldChangeRejected(String message) {
    super(message);
  }
}
