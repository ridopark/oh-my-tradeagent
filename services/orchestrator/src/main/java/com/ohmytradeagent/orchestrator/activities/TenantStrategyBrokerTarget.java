package com.ohmytradeagent.orchestrator.activities;

/**
 * Phase 2 (kill-switch realized re-source): one of a tenant's strategies paired with the {@code
 * broker_target} its journal lives on. Returned by {@link
 * AccountPnlActivities#tenantStrategyBrokerTargets} so the account kill-switch WORKFLOW can build a
 * broker_target-routed {@link com.ohmytradeagent.contract.activities.DailyPnlExecActivity} stub per
 * strategy and sum the per-strategy realized figures itself (routing must live in workflow code).
 *
 * <p>Supports mixed-broker_target tenants (each strategy routes to its own {@code broker-<target>}
 * queue). {@code brokerTarget} may be null/blank when a strategy's config cannot be resolved; the
 * workflow FAILS CLOSED on such a strategy (guardrail G2) rather than summing a partial.
 */
public record TenantStrategyBrokerTarget(String strategyId, String brokerTarget) {}
