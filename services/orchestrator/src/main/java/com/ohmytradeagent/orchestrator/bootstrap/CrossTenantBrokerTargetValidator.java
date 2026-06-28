package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Issue #323 part (b): config-load invariant guarding the notional-cap basis against silent
 * cross-tenant loosening. The cap basis aggregates a tenant's running PositionWorkflows on a {@code
 * broker_target} (cost-basis {@code cash + sum_open_notional}); if two distinct tenants shared one
 * brokerage account, tenant A's gate would be unaware of tenant B's open notional on that account
 * (cross-tenant isolation forbids B's positions from entering A's snapshot) → the account-level cap
 * would be silently loosened. Rejecting the misconfiguration at boot keeps the cap basis sound.
 *
 * <p><b>Strict mode (default).</b> A {@code broker_target} is owned by exactly ONE tenant; multiple
 * strategies of one tenant may share it, but two distinct tenants may not. This is the original
 * #323 rule and the behavior whenever {@code multitenant.broker-accounts.enabled=false} (the
 * default), so the live single-tenant path is unchanged.
 *
 * <p><b>P4-c-a shared-account mode (dark, gated).</b> When {@code
 * multitenant.broker-accounts.enabled=true} the rule generalizes to the brokerage-account identity:
 * many tenants MAY share a {@code broker_target} provided each declares a non-blank,
 * mutually-distinct {@code broker_account_id} (and a single tenant's strategies on one {@code
 * broker_target} declare a consistent account). Any absent or duplicated account fails boot closed
 * — an account that can't be proven distinct can't prove cap isolation. This mode is INERT by
 * default and MUST stay so until P4-c-b makes the runtime account-wide reads (AccountSnapshot /
 * PreTradeCheck / Reconciliation) per-tenant and cross-checks the declared {@code
 * broker_account_id} against the creds' authenticated account; relaxing the boot guard before the
 * runtime is per-tenant would re-open the very cap-loosening this validator exists to prevent. The
 * flag is the structural gate that keeps the relaxation unreachable until then.
 *
 * <p>Wired on the {@link TenantStrategyScanner}-fed boot path (see {@link
 * CrossTenantBrokerTargetBootstrapper}). Strategies with an absent {@code broker_target} are
 * skipped — they cannot collide. Config is resolved through the active {@link StrategyRegistry}; a
 * scanned strategy whose config cannot load throws BEFORE it can be classified (fail closed).
 */
public final class CrossTenantBrokerTargetValidator {

  private CrossTenantBrokerTargetValidator() {}

  /** Strict mode (default): a {@code broker_target} is owned by exactly one tenant. */
  public static void validate(Path tenantsDir, StrategyRegistry registry) {
    validate(tenantsDir, registry, false);
  }

  /**
   * Validates the cross-tenant broker_target invariant. {@code sharedBrokerAccounts=false} is the
   * strict #323 rule (one tenant per {@code broker_target}); {@code true} is the P4-c-a shared mode
   * (many tenants per {@code broker_target} iff each declares a distinct {@code
   * broker_account_id}). Throws {@link IllegalStateException} on a violation; no-op when the
   * tenants dir does not exist.
   */
  public static void validate(
      Path tenantsDir, StrategyRegistry registry, boolean sharedBrokerAccounts) {
    ownerByBrokerTarget(tenantsDir, registry, sharedBrokerAccounts);
  }

  /** Strict mode (default). See {@link #ownerByBrokerTarget(Path, StrategyRegistry, boolean)}. */
  public static Map<String, String> ownerByBrokerTarget(
      Path tenantsDir, StrategyRegistry registry) {
    return ownerByBrokerTarget(tenantsDir, registry, false);
  }

  /**
   * Builds the {@code broker_target -> owning (first-seen) tenant} map, throwing if the active
   * mode's invariant is violated. Returns an empty map when the tenants dir is missing. A scanned
   * strategy whose config cannot load via {@code registry.get} throws (fail closed) before it can
   * be classified.
   */
  public static Map<String, String> ownerByBrokerTarget(
      Path tenantsDir, StrategyRegistry registry, boolean sharedBrokerAccounts) {
    return sharedBrokerAccounts
        ? ownerBySharedBrokerAccounts(tenantsDir, registry)
        : ownerByStrictBrokerTarget(tenantsDir, registry);
  }

  /** The original #323 rule: a {@code broker_target} is owned by exactly one tenant. */
  private static Map<String, String> ownerByStrictBrokerTarget(
      Path tenantsDir, StrategyRegistry registry) {
    Map<String, String> ownerByTarget = new LinkedHashMap<>();
    if (!Files.exists(tenantsDir)) {
      return ownerByTarget;
    }
    for (TenantStrategy ts : TenantStrategyScanner.scan(tenantsDir)) {
      String brokerTarget = brokerTargetOf(registry, ts);
      if (brokerTarget == null) {
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

  /**
   * P4-c-a shared-account rule: many tenants may share a {@code broker_target} iff each declares a
   * non-blank, mutually-distinct {@code broker_account_id} (and a single tenant's strategies on one
   * target declare a consistent account).
   */
  private static Map<String, String> ownerBySharedBrokerAccounts(
      Path tenantsDir, StrategyRegistry registry) {
    Map<String, String> firstTenantByTarget = new LinkedHashMap<>();
    // broker_target -> (tenant -> declared account-or-null), first-seen order.
    Map<String, Map<String, String>> accountByTenantByTarget = new LinkedHashMap<>();
    if (!Files.exists(tenantsDir)) {
      return firstTenantByTarget;
    }
    for (TenantStrategy ts : TenantStrategyScanner.scan(tenantsDir)) {
      StrategyConfig cfg = registry.get(ts.tenantId(), ts.strategyId());
      String brokerTarget = brokerTargetOf(cfg);
      if (brokerTarget == null) {
        continue;
      }
      String account = trimToNull(cfg.getBrokerAccountId());
      firstTenantByTarget.putIfAbsent(brokerTarget, ts.tenantId());
      Map<String, String> byTenant =
          accountByTenantByTarget.computeIfAbsent(brokerTarget, k -> new LinkedHashMap<>());
      if (byTenant.containsKey(ts.tenantId())) {
        String prior = byTenant.get(ts.tenantId());
        if (!Objects.equals(prior, account)) {
          throw new IllegalStateException(
              "Intra-tenant broker_account_id conflict (#323/P4-c): tenant '"
                  + ts.tenantId()
                  + "' declares different broker_account_id values ('"
                  + prior
                  + "' vs '"
                  + account
                  + "') across strategies sharing broker_target='"
                  + brokerTarget
                  + "'. A tenant's strategies on one broker_target must declare the same account so"
                  + " the cap basis aggregates a single brokerage account.");
        }
      } else {
        byTenant.put(ts.tenantId(), account);
      }
    }
    for (Map.Entry<String, Map<String, String>> group : accountByTenantByTarget.entrySet()) {
      validateSharedGroup(group.getKey(), group.getValue());
    }
    return firstTenantByTarget;
  }

  /** A {@code broker_target} touched by >1 distinct tenant needs distinct non-blank accounts. */
  private static void validateSharedGroup(
      String brokerTarget, Map<String, String> accountByTenant) {
    if (accountByTenant.size() <= 1) {
      return; // single tenant on this broker_target → always sound, no account needed.
    }
    Set<String> seenAccounts = new HashSet<>();
    for (Map.Entry<String, String> te : accountByTenant.entrySet()) {
      String account = te.getValue();
      if (account == null) {
        throw new IllegalStateException(
            "Cross-tenant broker_target conflict (#323/P4-c): broker_target='"
                + brokerTarget
                + "' is shared by multiple tenants but tenant '"
                + te.getKey()
                + "' declares no broker_account_id — cannot prove account isolation. Every tenant"
                + " sharing a broker_target must declare a distinct broker_account_id. Tenants: "
                + accountByTenant.keySet());
      }
      if (!seenAccounts.add(account)) {
        throw new IllegalStateException(
            "Cross-tenant broker_target conflict (#323/P4-c): broker_target='"
                + brokerTarget
                + "' has two tenants declaring the same broker_account_id='"
                + account
                + "' — distinct tenants must trade distinct brokerage accounts or the account-level"
                + " cap is silently loosened. Tenants: "
                + accountByTenant.keySet());
      }
    }
  }

  private static String brokerTargetOf(StrategyRegistry registry, TenantStrategy ts) {
    return brokerTargetOf(registry.get(ts.tenantId(), ts.strategyId()));
  }

  private static String brokerTargetOf(StrategyConfig cfg) {
    StrategyConfig.BrokerTarget target = cfg.getBrokerTarget();
    String brokerTarget = target == null ? null : target.value();
    return (brokerTarget == null || brokerTarget.isBlank()) ? null : brokerTarget;
  }

  private static String trimToNull(String s) {
    if (s == null) {
      return null;
    }
    String trimmed = s.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
