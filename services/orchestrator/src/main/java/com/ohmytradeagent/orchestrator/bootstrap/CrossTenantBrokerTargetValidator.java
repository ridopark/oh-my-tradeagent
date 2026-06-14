package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Issue #323 part (b): config-load invariant enforcing the operator's Option-B ownership model — a
 * {@code broker_target} is owned by exactly <b>one tenant</b>. Multiple strategies of the same
 * tenant may share a {@code broker_target} (that is the whole point of #323's tenant-account-wide
 * cap basis), but two <b>distinct</b> tenants mapping to the same {@code broker_target} is
 * forbidden and fails fast at startup.
 *
 * <p>Rationale: the #323 cap basis aggregates a tenant's running PositionWorkflows on a {@code
 * broker_target}. If two tenants shared one {@code broker_target}, tenant A's gate would be unaware
 * of tenant B's open notional on the same brokerage account (cross-tenant isolation forbids tenant
 * B's positions from entering tenant A's snapshot) — so the account-level cap would be silently
 * loosened. Rejecting the misconfiguration at boot keeps the cap basis sound.
 *
 * <p>Wired on the {@link TenantStrategyScanner}-fed boot path (see {@link
 * CrossTenantBrokerTargetBootstrapper}) so a violating tenants tree fails fast before any workflow
 * starts. Strategies with an absent {@code broker_target} are skipped — they cannot collide.
 *
 * <p>P0c-b2: broker_target is resolved through the active {@link StrategyRegistry} rather than read
 * directly off disk. The scan enumerates which {@code (tenant, strategy)} pairs to classify; a
 * scanned strategy whose config cannot load throws BEFORE it can be classified live/paper, and that
 * throw MUST propagate (boot fails closed) — never degrade to a skip that could corrupt the
 * ownership map.
 */
public final class CrossTenantBrokerTargetValidator {

  private CrossTenantBrokerTargetValidator() {}

  /**
   * Throws {@link IllegalStateException} if two distinct tenants map to the same {@code
   * broker_target}. No-op when the tenants dir does not exist.
   */
  public static void validate(Path tenantsDir, StrategyRegistry registry) {
    ownerByBrokerTarget(tenantsDir, registry);
  }

  /**
   * Builds the {@code broker_target -> owning tenant} map, throwing if any {@code broker_target} is
   * claimed by two distinct tenants. Returns an empty map when the tenants dir is missing. A
   * scanned strategy whose config cannot load via {@code registry.get} throws (fail closed) before
   * it can be classified.
   */
  public static Map<String, String> ownerByBrokerTarget(
      Path tenantsDir, StrategyRegistry registry) {
    Map<String, String> ownerByTarget = new LinkedHashMap<>();
    if (!Files.exists(tenantsDir)) {
      return ownerByTarget;
    }
    for (TenantStrategyScanner.TenantStrategy ts : TenantStrategyScanner.scan(tenantsDir)) {
      StrategyConfig cfg = registry.get(ts.tenantId(), ts.strategyId());
      StrategyConfig.BrokerTarget target = cfg.getBrokerTarget();
      String brokerTarget = target == null ? null : target.value();
      if (brokerTarget == null || brokerTarget.isBlank()) {
        continue;
      }
      String existingOwner = ownerByTarget.putIfAbsent(brokerTarget, ts.tenantId());
      if (existingOwner != null && !existingOwner.equals(ts.tenantId())) {
        throw new IllegalStateException(
            "Cross-tenant broker_target conflict (#323): broker_target='"
                + brokerTarget
                + "' is mapped by two distinct tenants ('"
                + existingOwner
                + "' and '"
                + ts.tenantId()
                + "'). A broker_target is owned by exactly one tenant; multiple strategies of one"
                + " tenant may share it, but two tenants may not. Reassign one tenant to a distinct"
                + " broker_target.");
      }
    }
    return ownerByTarget;
  }
}
