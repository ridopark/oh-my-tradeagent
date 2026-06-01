package com.ohmytradeagent.orchestrator.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ohmytradeagent.contract.StrategyConfig;
import java.io.IOException;
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
 */
public final class CrossTenantBrokerTargetValidator {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private CrossTenantBrokerTargetValidator() {}

  /**
   * Throws {@link IllegalStateException} if two distinct tenants map to the same {@code
   * broker_target}. No-op when the tenants dir does not exist.
   */
  public static void validate(Path tenantsDir) {
    ownerByBrokerTarget(tenantsDir);
  }

  /**
   * Builds the {@code broker_target -> owning tenant} map, throwing if any {@code broker_target} is
   * claimed by two distinct tenants. Returns an empty map when the tenants dir is missing.
   */
  public static Map<String, String> ownerByBrokerTarget(Path tenantsDir) {
    Map<String, String> ownerByTarget = new LinkedHashMap<>();
    if (!Files.exists(tenantsDir)) {
      return ownerByTarget;
    }
    for (TenantStrategyScanner.TenantStrategy ts : TenantStrategyScanner.scan(tenantsDir)) {
      String brokerTarget = readBrokerTarget(tenantsDir, ts.tenantId(), ts.strategyId());
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

  private static String readBrokerTarget(Path tenantsDir, String tenantId, String strategyId) {
    Path file = tenantsDir.resolve(tenantId).resolve("strategies").resolve(strategyId + ".yaml");
    try {
      StrategyConfig cfg = YAML.readValue(file.toFile(), StrategyConfig.class);
      StrategyConfig.BrokerTarget target = cfg.getBrokerTarget();
      return target == null ? null : target.value();
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed to read broker_target from " + file.toAbsolutePath(), e);
    }
  }
}
