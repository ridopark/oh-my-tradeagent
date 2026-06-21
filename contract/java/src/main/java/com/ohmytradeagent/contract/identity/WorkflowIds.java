package com.ohmytradeagent.contract.identity;

/**
 * Canonical workflow-ID + Search-Attribute shapes. The orchestrator's bootstrappers, risk gate,
 * cascade Activity, and the api-gateway controllers all build the same strings; centralising them
 * here is the single source of truth — a future rename (e.g. tenancy prefix) updates one file.
 *
 * <p>Also provides {@link #escapeForVisibilityQuery(String)} for safely interpolating
 * tenant/strategy/symbol values into Temporal Visibility query strings.
 */
public final class WorkflowIds {

  private WorkflowIds() {}

  /** Workflow ID for the {@code KillSwitchWorkflow} owning a {@code (tenant, strategy)} pair. */
  public static String killswitch(String tenantId, String strategyId) {
    return tenantStrategy(tenantId, strategyId) + "/killswitch";
  }

  /**
   * Workflow ID for the {@code AccountKillSwitchWorkflow} owning a whole tenant's account-level loss
   * cap (Phase 6). Deliberately does NOT route through {@link #tenantStrategy}: the account cap spans
   * EVERY strategy on the tenant's shared {@code broker_target}, so it is tenant-scoped and
   * strategy-agnostic — there is no {@code s-} segment. One per tenant, started by {@code
   * KillSwitchBootstrapper} alongside the per-(tenant,strategy) kill switches.
   */
  public static String accountKillswitch(String tenantId) {
    return "t-" + tenantId + "/account/killswitch";
  }

  /** Value of the {@code TenantStrategy} custom Search Attribute. */
  public static String tenantStrategy(String tenantId, String strategyId) {
    return "t-" + tenantId + "/s-" + strategyId;
  }

  /**
   * Workflow ID for the short-lived {@code BrokerCredentialAuditWorkflow} that records a metadata-
   * only audit of a tenant broker-credential write/rotation (UI-P2-a).
   *
   * <p>Deliberately does NOT route through {@link #tenantStrategy}: a credential is {@code (tenant,
   * provider)}-scoped and strategy-agnostic, so there is no {@code s-} segment. The {@code _broker}
   * chain identity matches what P6-d's audit committed to. The {@code correlationId} embedded in
   * the id is the dedup key — a retried api-gateway call collides on {@code REJECT_DUPLICATE}
   * rather than double-auditing the same write.
   */
  public static String brokerCredentialAudit(String tenantId, String correlationId) {
    return "t-" + tenantId + "/_broker/cred-audit/" + correlationId;
  }

  /**
   * Workflow ID for the short-lived {@code StrategyConfigUpdateWorkflow} that performs the UI-P3-b
   * dark-gated, reduce-or-hold-risk runtime config write for {@code (tenant, strategy)}.
   *
   * <p>Routes through {@link #tenantStrategy} (the write IS strategy-scoped, unlike the {@code
   * (tenant, provider)} credential audit) and appends the {@code correlationId} so a retried
   * api-gateway call collides on {@code REJECT_DUPLICATE} rather than double-writing the same
   * config (the underlying CAS is not idempotent-append).
   */
  public static String strategyConfigUpdate(
      String tenantId, String strategyId, String correlationId) {
    return tenantStrategy(tenantId, strategyId) + "/cfg-write/" + correlationId;
  }

  /**
   * Workflow ID prefix for a {@code PositionWorkflow}; {@code entrySignalId} disambiguates re-BTOs.
   */
  public static String position(
      String tenantId, String strategyId, String occSymbol, String entrySignalId) {
    return tenantStrategy(tenantId, strategyId) + "/pos/" + occSymbol + "/" + entrySignalId;
  }

  /** Workflow ID prefix for {@code ReconciliationWorkflow} runs. The scheduler appends a run-id. */
  public static String reconciliationPrefix(
      String tenantId, String strategyId, String brokerTarget) {
    return tenantStrategy(tenantId, strategyId) + "/recon/" + brokerTarget + "/";
  }

  /**
   * Workflow ID for the short-lived {@code AdoptionWorkflow} that adopts the orphaned {@code occ}.
   * Issue #285: keyed on the OCC (not a random id) so a double-click maps to one execution —
   * concurrent starts collide on this id and the adoption workflow's own idempotency guard ({@code
   * ALREADY_OWNED}) makes a post-completion re-run a safe no-op. The compact OCC keeps the id
   * stable regardless of whether the operator supplies the padded or compact form.
   */
  public static String adoption(String tenantId, String strategyId, String occ) {
    String compact = occ == null ? "" : occ.replace(" ", "");
    return tenantStrategy(tenantId, strategyId) + "/adopt/" + compact;
  }

  /**
   * Workflow ID of the {@code CopytradeSignalWorkflow} for a given signal. The Python emitter
   * builds the same shape in {@code services/signal-source-discord/.../emitter.py:workflow_id_for}
   * — keep the two in lockstep (cross-language constant sharing is a separate cleanup).
   */
  public static String copytradeSignal(String tenantId, String strategyId, String signalId) {
    return tenantStrategy(tenantId, strategyId) + "/sig/" + signalId;
  }

  /**
   * Escape a value before interpolating it into a Temporal Visibility query. Visibility query
   * grammar uses single-quoted strings; a single-quote inside a value would break the query (or, in
   * a hostile setting, allow query injection). Doubles every quote per ANSI-SQL convention.
   */
  public static String escapeForVisibilityQuery(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("'", "''");
  }
}
