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
   * Workflow ID for the {@code AccountKillSwitchWorkflow} owning a whole tenant's account-level
   * loss cap (Phase 6). Deliberately does NOT route through {@link #tenantStrategy}: the account
   * cap spans EVERY strategy on the tenant's shared {@code broker_target}, so it is tenant-scoped
   * and strategy-agnostic — there is no {@code s-} segment. One per tenant, started by {@code
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
   * Workflow ID for the {@code WatchlistDigestMarkerWorkflow} — a per-{@code (tenant, etDate)}
   * dedup token that lets the daily watchlist digest post exactly once per tenant even though the
   * mirror fans out one {@code WatchlistMirrorWorkflow} per {@code (tenant, strategy)}.
   * Deliberately does NOT route through {@link #tenantStrategy}: the digest is a per-tenant
   * concern, so there is no {@code s-} segment (mirrors {@link #accountKillswitch}). Started under
   * {@code REJECT_DUPLICATE} — the first fan-out entry wins the post; the rest collide and skip.
   * {@code etDate} is the ISO ET trading date.
   */
  public static String watchlistDigest(String tenantId, String etDate) {
    return "t-" + tenantId + "/wl/" + etDate + "/digest";
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
   * Workflow ID for the short-lived {@code TenantConfigUpdateWorkflow} that performs the
   * account-loss-cap-db Phase 3 dark-gated, tighten-only account-cap write for a whole {@code
   * tenant}.
   *
   * <p>Deliberately does NOT route through {@link #tenantStrategy}: the account cap spans every
   * strategy on the tenant's shared {@code broker_target}, so it is tenant-scoped and
   * strategy-agnostic (mirrors {@link #accountKillswitch} — there is no {@code s-} segment).
   * Appends the {@code correlationId} so a retried api-gateway call collides on {@code
   * REJECT_DUPLICATE} rather than double-applying the non-idempotent CAS.
   */
  public static String tenantConfigUpdate(String tenantId, String correlationId) {
    return "t-" + tenantId + "/account/cfg-write/" + correlationId;
  }

  /**
   * Workflow ID for the short-lived {@code StrategyConfigCreateWorkflow} that performs the Phase
   * I-1b (operator-account-onboarding) dark-gated create-tenant INSERT for {@code (tenant,
   * strategy)} — the first config row at version 1.
   *
   * <p>Routes through {@link #tenantStrategy} (the create IS strategy-scoped) and appends the
   * {@code correlationId} so a retried api-gateway call collides on {@code REJECT_DUPLICATE} and
   * returns the original run's result rather than re-running the INSERT. A genuinely new create of
   * an already-existing tenant uses a fresh {@code correlationId}, runs, and the {@code ON CONFLICT
   * DO NOTHING} INSERT yields {@code ALREADY_EXISTS}. Mirrors {@link #strategyConfigUpdate}'s
   * shape.
   */
  public static String strategyConfigCreate(
      String tenantId, String strategyId, String correlationId) {
    return tenantStrategy(tenantId, strategyId) + "/cfg-create/" + correlationId;
  }

  /**
   * Workflow ID for the short-lived {@code LiveActivationWorkflow} that performs the Phase F
   * (operator-account-onboarding) dark-gated one-click live activation / deactivation for {@code
   * (tenant, strategy)}.
   *
   * <p>Routes through {@link #tenantStrategy} (the activation IS strategy-scoped) and appends a
   * fresh per-call {@code correlationId} so each click is its own run — re-activation (re-arming
   * after the 30-day window) is intentionally a new workflow, not a {@code REJECT_DUPLICATE}
   * collision. A double-submit is therefore NOT deduped, but that is harmless: the order-time gate
   * reads the NEWEST matching {@code LivePromotionApproved} row, so a redundant approval row
   * changes nothing. Mirrors {@link #strategyConfigUpdate}'s shape.
   */
  public static String liveActivation(String tenantId, String strategyId, String correlationId) {
    return tenantStrategy(tenantId, strategyId) + "/live-activation/" + correlationId;
  }

  /**
   * Workflow ID for the operator {@code TenantDeleteWorkflow} teardown of one {@code (tenant,
   * strategy)} (PLAN-2026-07-03, Phase 4). Routes through {@link #tenantStrategy} (the teardown IS
   * strategy-scoped) and appends a fresh per-request {@code correlationId} so re-runs get a
   * distinct id rather than colliding.
   */
  public static String tenantDelete(String tenantId, String strategyId, String correlationId) {
    return tenantStrategy(tenantId, strategyId) + "/tenant-delete/" + correlationId;
  }

  /**
   * Workflow ID for the short-lived {@code AuditEmitWorkflow} that records ONE tenant-delete
   * lifecycle event (PLAN-2026-07-03, Phase 4). Deliberately does NOT route through {@link
   * #tenantStrategy}: the emit is keyed by {@code correlationId} + event {@code kind}, and
   * per-event uniqueness comes from the caller-supplied {@code uuid} (best-effort audit, no dedup
   * reuse policy). The random {@code uuid} stays at the caller so this stays a pure function.
   */
  public static String auditEmit(String correlationId, String kind, String uuid) {
    return "audit-emit/" + correlationId + "/" + kind + "/" + uuid;
  }

  /**
   * Workflow ID prefix for a {@code PositionWorkflow}; {@code entrySignalId} disambiguates re-BTOs.
   */
  public static String position(
      String tenantId, String strategyId, String occSymbol, String entrySignalId) {
    return tenantStrategy(tenantId, strategyId) + POS_SEGMENT + occSymbol + "/" + entrySignalId;
  }

  /** The {@code /pos/} marker that {@link #position} writes and {@link #occFromPosition} reads. */
  private static final String POS_SEGMENT = "/pos/";

  /**
   * The OCC embedded in a {@code PositionWorkflow} id, or {@code null} if this is not one.
   *
   * <p>#718: recon adoption mints a NEW workflow id for the SAME position, so an operator's cached
   * id can be dead while the position is very much open. The dead id still names the contract, and
   * that is what lets a write surface find the live owner instead of reporting the position closed.
   * Parsing stops at the first separator after the OCC because an entry signal id may contain
   * slashes of its own (watchlist ids look like {@code wl/<date>/<sym>/<right>}), while the OCC
   * never does.
   *
   * <p>Returns {@code null} rather than a best guess for anything malformed — a caller that cannot
   * name the contract with certainty must not act on one.
   */
  public static String occFromPosition(String workflowId) {
    if (workflowId == null) {
      return null;
    }
    int start = workflowId.indexOf(POS_SEGMENT);
    if (start < 0) {
      return null;
    }
    start += POS_SEGMENT.length();
    int end = workflowId.indexOf('/', start);
    if (end < 0) {
      // No trailing entry-signal-id segment: not a well-formed position id, so refuse to guess.
      return null;
    }
    String occ = workflowId.substring(start, end);
    return occ.isBlank() ? null : occ;
  }

  /**
   * The entry signal id embedded in a {@code PositionWorkflow} id, or {@code null} if this is not
   * one.
   *
   * <p>#783: the trade-context recorder keys its rows by {@code (signal_id, tenant_id)}; the id
   * segment AFTER the OCC is exactly the {@code entrySignalId} that {@link #position} wrote, so no
   * workflow query is needed to recover it. Everything after the first separator following the OCC
   * belongs to the signal id — watchlist signal ids contain slashes of their own (they look like
   * {@code wl/<date>/<sym>/<right>}), while the OCC never does.
   *
   * <p>Returns {@code null} rather than a best guess for anything malformed — a caller that cannot
   * name the signal with certainty must not record against one.
   */
  public static String entrySignalIdFromPosition(String workflowId) {
    if (occFromPosition(workflowId) == null) {
      return null;
    }
    int start = workflowId.indexOf(POS_SEGMENT) + POS_SEGMENT.length();
    String signalId = workflowId.substring(workflowId.indexOf('/', start) + 1);
    return signalId.isBlank() ? null : signalId;
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
   * Redis set key holding the armed {@code WatchlistTriggerWorkflow} ids for a {@code (tenant,
   * strategy, et_date)} on a given trading day. The orchestrator SADDs the leg's workflow id here
   * on arm; the BFF SMEMBERS it to enumerate the armed watchlist without a SQL-visibility {@code
   * listExecutions} (which lags under postgres load). Raw {@code tenant}/{@code strategy} form (no
   * {@code t-}/{@code s-} prefixes) matching {@code PositionLookupActivitiesImpl}'s {@code pos:}
   * key.
   */
  public static String armedWatchlistCacheKey(
      String tenantId, String strategyId, java.time.LocalDate etDate) {
    return "wl-armed:" + tenantId + ":" + strategyId + ":" + etDate;
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
