package com.ohmytradeagent.tdbff.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ohmytradeagent.contract.CopytradeEntryStatus;
import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.tdbff.entries.OccParser;
import com.ohmytradeagent.tdbff.entries.OccParser.InvalidOccException;
import com.ohmytradeagent.tdbff.entries.OccParser.ParsedOcc;
import com.ohmytradeagent.tdbff.platform.StrategyConfigReader;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient;
import com.ohmytradeagent.tdbff.proximity.MarketDataQuoteClient.OptionQuote;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PLAN-2026-08-10-live-manual-bto: operator-initiated ENTRY from the /live dashboard.
 *
 * <ul>
 *   <li>{@code GET /api/entries/quote?occ=…} — parse the operator's contract string and snapshot
 *       its NBBO, for the confirm step.
 *   <li>{@code POST /api/entries/manual} — start a {@code CopytradeSignalWorkflow} with a synthetic
 *       {@code CopytradeSignalPayload{action:BTO, source:manual}}. This is the WHOLE point of the
 *       design: the entry then runs the exact path a Discord signal runs — strategy-enabled gate,
 *       pre-trade check, notional cap, buying power, LIVE promotion gate, order journal, and the
 *       PositionWorkflow handoff with every exit (STC, chandelier trail, EOD/expiry timers). No
 *       part of the entry path is forked for manual orders.
 *   <li>{@code GET /api/entries/{signalId}/status} — the workflow's {@code entryStatus} Query, so a
 *       gate rejection is visible instead of looking identical to success.
 * </ul>
 *
 * <p><b>Real-money guards</b>, mirroring {@link PositionsController}: its own dark-launch flag
 * ({@code entries.manual.write-enabled}), fail-closed tenant resolution (the tenant is NEVER a
 * client parameter), the shared {@link WorkflowWriteGuards} tenant-prefix check on the status read,
 * a server-side re-validation of the strategy against the tenant's OWN {@code strategy_config}
 * rows, and a stale-quote refusal so a price the operator confirmed cannot be filled after the
 * market has moved away from it.
 */
@RestController
@RequestMapping("/api/entries")
public class ManualEntryController {

  private static final Logger log = LoggerFactory.getLogger(ManualEntryController.class);

  /**
   * How long a quote shown in the confirm step stays actionable. The operator sees a price, then
   * decides; past this the price they agreed to is no longer the market and they must re-quote.
   */
  private static final Duration QUOTE_MAX_AGE = Duration.ofSeconds(30);

  /**
   * How far the ask may move UP between the quote the operator confirmed and the submit before the
   * order is refused. Only the upward direction is checked: this is a BUY, so a lower ask is a
   * better fill and never a reason to refuse. 10% is deliberately loose enough not to fight normal
   * option jitter and tight enough to stop a gap.
   */
  private static final BigDecimal MAX_ASK_DRIFT = new BigDecimal("1.10");

  private final WorkflowClient client;
  private final TenantContext ctx;
  private final MarketDataQuoteClient quotes;
  private final StrategyConfigReader strategyConfigs;
  private final String orchestratorTaskQueue;

  /**
   * Tenants allowed to use manual entry, on top of the dark flag. EMPTY (the default) means the
   * flag alone governs — i.e. every tenant.
   *
   * <p>This exists because the BFF is ONE deployment serving every tenant, so {@code
   * entries.manual.write-enabled} alone is all-or-nothing: flipping it would arm the real-money
   * tenants at the same instant as the paper one, and the plan's paper-canary-first sequence would
   * be impossible to actually run. With this set to {@code staging_paper}, the canary is genuinely
   * isolated; widening it later is a second, deliberate operator action.
   */
  private final Set<String> allowedTenants;

  /**
   * Server-side dark-launch gate for the manual-entry surface (default false). This endpoint can
   * OPEN a real-money position — the only BFF route that can — so while off both the write and its
   * quote preview 404 server-side; the write surface is not merely hidden on the dashboard. Flipped
   * true only alongside the dashboard's {@code MANUAL_ENTRY_WRITE_ENABLED}.
   */
  private final boolean manualEntryWriteEnabled;

  public ManualEntryController(
      WorkflowClient client,
      TenantContext ctx,
      MarketDataQuoteClient quotes,
      StrategyConfigReader strategyConfigs,
      @Value("${temporal.orchestrator-task-queue:orchestrator-core}") String orchestratorTaskQueue,
      @Value("${entries.manual.write-enabled:false}") boolean manualEntryWriteEnabled,
      @Value("${entries.manual.allowed-tenants:}") String allowedTenants) {
    this.client = client;
    this.ctx = ctx;
    this.quotes = quotes;
    this.strategyConfigs = strategyConfigs;
    this.orchestratorTaskQueue = orchestratorTaskQueue;
    this.manualEntryWriteEnabled = manualEntryWriteEnabled;
    this.allowedTenants =
        Arrays.stream(allowedTenants.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Whether this tenant may use manual entry. Callers return the SAME {@code manual_entry_disabled}
   * 404 as the flag-off path on purpose: to a tenant outside the allowlist the feature simply does
   * not exist, and the response is not an oracle for which other tenants have it.
   */
  private boolean allowedForTenant(String tenant) {
    return allowedTenants.isEmpty() || allowedTenants.contains(tenant);
  }

  /** Parsed contract + live NBBO for the confirm step. */
  @GetMapping("/quote")
  public ResponseEntity<Map<String, Object>> quote(
      HttpServletRequest req, @RequestParam("occ") String occ) {
    if (!manualEntryWriteEnabled) {
      return disabled();
    }
    if (!allowedForTenant(ctx.tenantId(req))) { // fail-closed 401 first — part of a write flow
      return disabled();
    }

    ParsedOcc parsed;
    try {
      parsed = OccParser.parse(occ);
    } catch (InvalidOccException e) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "invalid_occ", "detail", String.valueOf(e.getMessage())));
    }

    OptionQuote q = quotes.optionQuote(parsed.occ());
    if (!isPriceable(q)) {
      // No usable ask means no anchor for the marketable limit. Refuse rather than show the
      // operator a half-populated confirm step they might submit anyway.
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(Map.of("error", "quote_unavailable", "occ", parsed.occ()));
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("occ", parsed.occ());
    body.put("underlying", parsed.ticker());
    body.put("expiry", parsed.expiry().toString());
    body.put("strike", parsed.strike());
    body.put("right", parsed.right());
    body.put("bid", q.bid());
    body.put("mid", q.mid());
    body.put("ask", q.ask());
    body.put("quoted_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
    return ResponseEntity.ok(body);
  }

  /** Start the entry. 202 == the workflow is running; the gates run inside it. */
  @PostMapping("/manual")
  public ResponseEntity<Map<String, Object>> manual(
      HttpServletRequest req, @RequestBody(required = false) ManualEntryPayload body) {
    if (!manualEntryWriteEnabled) {
      return disabled();
    }
    String tenant = ctx.tenantId(req); // fail-closed 401 — the tenant is NEVER a client parameter
    if (!allowedForTenant(tenant)) {
      return disabled();
    }

    if (body == null) {
      throw new IllegalArgumentException("request body is required");
    }
    String idempotencyKey = require(body.idempotencyKey(), "idempotency_key");
    String strategyId = require(body.strategyId(), "strategy_id");
    if (body.qty() == null || body.qty() < 1) {
      throw new IllegalArgumentException("qty must be at least 1");
    }
    if (body.quotedAsk() == null || body.quotedAsk().signum() <= 0) {
      throw new IllegalArgumentException("quoted_ask must be greater than zero");
    }

    ParsedOcc parsed;
    try {
      parsed = OccParser.parse(body.occ());
    } catch (InvalidOccException e) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "invalid_occ", "detail", String.valueOf(e.getMessage())));
    }

    // The strategy must be one this tenant actually owns. Without this, a caller could name any
    // strategy id and mint a doomed workflow under it; with it, an unknown id is a clean 403 and
    // the workflow id we build is always inside the tenant's own namespace.
    if (!ownsStrategy(tenant, strategyId)) {
      log.warn(
          "manual entry refused: unknown_strategy tenant={} strategy_id={}", tenant, strategyId);
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "unknown_strategy"));
    }

    ResponseEntity<Map<String, Object>> staleRefusal = refuseIfQuoteStale(body.quotedAt());
    if (staleRefusal != null) {
      return staleRefusal;
    }

    // Re-snapshot at submit: the operator confirmed a price seconds ago, and the ANCHOR for the
    // marketable limit must be the market now, not then. A fresh ask that has run away from the
    // confirmed one is refused rather than filled.
    OptionQuote fresh = quotes.optionQuote(parsed.occ());
    if (!isPriceable(fresh)) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(Map.of("error", "quote_unavailable", "occ", parsed.occ()));
    }
    BigDecimal ceiling = body.quotedAsk().multiply(MAX_ASK_DRIFT);
    if (fresh.ask().compareTo(ceiling) > 0) {
      Map<String, Object> refusal = new LinkedHashMap<>();
      refusal.put("error", "quote_moved");
      refusal.put("confirmed_ask", body.quotedAsk());
      refusal.put("current_ask", fresh.ask());
      return ResponseEntity.status(HttpStatus.CONFLICT).body(refusal);
    }

    String operatorId = WorkflowWriteGuards.operatorId(req, tenant);
    String signalId = "manual:" + idempotencyKey;
    CopytradeSignalPayload payload =
        manualPayload(
            tenant,
            strategyId,
            signalId,
            idempotencyKey,
            operatorId,
            parsed,
            body.qty(),
            fresh.ask());

    String workflowId = WorkflowIds.copytradeSignal(tenant, strategyId, signalId);
    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setTaskQueue(orchestratorTaskQueue)
            .setWorkflowId(workflowId)
            // Same dedupe contract the Discord sidecar uses: a double-submit (or a retried request)
            // collides on the id rather than opening a SECOND real-money position.
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setSearchAttributes(
                Map.of("TenantStrategy", WorkflowIds.tenantStrategy(tenant, strategyId)))
            .build();

    WorkflowStub stub = client.newUntypedWorkflowStub("CopytradeSignalWorkflow", opts);
    try {
      // start(), never getResult(): the workflow lives ~90s (it awaits the fill or the entry TTL).
      // The dashboard polls /status instead of holding an HTTP request open for it.
      stub.start(payload);
    } catch (WorkflowExecutionAlreadyStarted e) {
      Map<String, Object> dup = new LinkedHashMap<>();
      dup.put("error", "duplicate_submission");
      dup.put("signal_id", signalId);
      dup.put("workflow_id", workflowId);
      return ResponseEntity.status(HttpStatus.CONFLICT).body(dup);
    }

    log.info(
        "manual entry started tenant={} strategy_id={} occ={} qty={} ask={} operator={}",
        tenant,
        strategyId,
        parsed.occ(),
        body.qty(),
        fresh.ask(),
        operatorId);

    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("signal_id", signalId);
    resp.put("workflow_id", workflowId);
    resp.put("occ", parsed.occ());
    resp.put("qty", body.qty());
    resp.put("anchor_ask", fresh.ask());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(resp);
  }

  /** Poll the entry's outcome. */
  @GetMapping("/{signalId}/status")
  public ResponseEntity<Map<String, Object>> status(
      HttpServletRequest req,
      @PathVariable("signalId") String signalId,
      @RequestParam("strategy_id") String strategyId) {
    if (!manualEntryWriteEnabled) {
      return disabled();
    }
    String tenant = ctx.tenantId(req);
    if (!allowedForTenant(tenant)) {
      return disabled();
    }

    String workflowId = WorkflowIds.copytradeSignal(tenant, strategyId, signalId);
    ResponseEntity<Map<String, Object>> refusal =
        WorkflowWriteGuards.refuseUnlessTenantOwned(
            tenant, workflowId, "/sig/", "not_a_signal_workflow_id");
    if (refusal != null) {
      return refusal;
    }

    CopytradeEntryStatus status =
        client.newUntypedWorkflowStub(workflowId).query("entryStatus", CopytradeEntryStatus.class);

    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("signal_id", signalId);
    resp.put("state", status.getState());
    resp.put("reason_code", status.getReasonCode());
    resp.put("reason_detail", status.getReasonDetail());
    resp.put("option_symbol", status.getOptionSymbol());
    resp.put("contracts", status.getContracts());
    resp.put("broker_order_id", status.getBrokerOrderId());
    resp.put("filled_qty", status.getFilledQty());
    resp.put("avg_fill_price", status.getAvgFillPrice());
    return ResponseEntity.ok(resp);
  }

  /**
   * The synthetic signal. Field choices that matter:
   *
   * <ul>
   *   <li>{@code price} = the FRESH ask. {@code BtoPricing.computeBtoLimit} then applies the
   *       tenant's own max_slippage_pct/abs to it, so the order is a marketable limit — a market
   *       order in behavior, with the tenant's existing slippage cap still enforced. Sizing, the
   *       notional cap and buying power all see the same number they see for a Discord signal.
   *   <li>{@code tail} = "" (EMPTY). A non-empty tail feeds {@code KeywordPartialMatcher} — the
   *       scale-in and de-risk cue vocabularies — and an operator's note must never trip those.
   *   <li>{@code source} = manual, which suppresses the edited-signal supersede downstream.
   * </ul>
   */
  private static CopytradeSignalPayload manualPayload(
      String tenant,
      String strategyId,
      String signalId,
      String messageId,
      String operatorId,
      ParsedOcc parsed,
      Integer qty,
      BigDecimal ask) {
    CopytradeSignalPayload p = new CopytradeSignalPayload();
    p.setSchemaVersion(1L);
    p.setTenantId(tenant);
    p.setStrategyId(strategyId);
    p.setSignalId(signalId);
    p.setMessageId(messageId);
    p.setAuthor(operatorId);
    p.setPostedAt(OffsetDateTime.now(ZoneOffset.UTC));
    p.setAction(CopytradeSignalPayload.Action.BTO);
    p.setTicker(parsed.ticker());
    p.setExpiry(parsed.expiry());
    p.setStrike(parsed.strike());
    p.setRight(CopytradeSignalPayload.Right.fromValue(parsed.right()));
    p.setPrice(ask);
    p.setTail("");
    p.setSource(CopytradeSignalPayload.Source.MANUAL);
    p.setQtyOverride(qty.longValue());
    p.setRawLine(
        "MANUAL BTO " + parsed.occ() + " qty=" + qty + " ask=" + ask + " operator=" + operatorId);
    return p;
  }

  /** True when {@code strategyId} is one of the tenant's OWN configured strategies. */
  private boolean ownsStrategy(String tenant, String strategyId) {
    List<Map<String, Object>> configs = strategyConfigs.configsForTenant(tenant);
    return configs.stream().anyMatch(c -> strategyId.equals(c.get("strategy_id")));
  }

  /**
   * Refuse when the confirm step's quote is older than {@link #QUOTE_MAX_AGE}. An unparseable or
   * absent timestamp is ALSO a refusal (fail-closed): we cannot establish that the operator saw a
   * current price, so we do not act on it.
   */
  private ResponseEntity<Map<String, Object>> refuseIfQuoteStale(String quotedAt) {
    Instant quoted;
    try {
      quoted = OffsetDateTime.parse(quotedAt).toInstant();
    } catch (DateTimeParseException | NullPointerException e) {
      throw new IllegalArgumentException("quoted_at must be an ISO-8601 timestamp");
    }
    Duration age = Duration.between(quoted, Instant.now());
    if (age.compareTo(QUOTE_MAX_AGE) > 0) {
      Map<String, Object> refusal = new LinkedHashMap<>();
      refusal.put("error", "quote_stale");
      refusal.put("age_secs", age.toSeconds());
      refusal.put("max_age_secs", QUOTE_MAX_AGE.toSeconds());
      return ResponseEntity.status(HttpStatus.CONFLICT).body(refusal);
    }
    return null;
  }

  /**
   * A quote we can actually anchor a BUY on: present, with a STRICTLY POSITIVE ask.
   *
   * <p>The zero check is not pedantry. A 0.00 ask reaches {@code BtoPricing} as a zero limit, and
   * {@code Sizing.rawContracts} then throws a bare {@code IllegalArgumentException} — which
   * CopytradeSignalWorkflowImpl's top-level catch deliberately does NOT catch (it only catches
   * TemporalFailure, so that plain runtime exceptions keep their loud workflow-TASK retry
   * behavior). The result would be an infinitely retrying workflow task with no audit, no alert,
   * and entryStatus pinned at PENDING forever — the exact invisible failure this feature's status
   * Query exists to prevent. Refuse at the door instead.
   */
  private static boolean isPriceable(OptionQuote q) {
    return q != null && q.ask() != null && q.ask().signum() > 0;
  }

  private static String require(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  /** Dark-launch: the surface is off. JSON body so the client can branch on the reason. */
  private static ResponseEntity<Map<String, Object>> disabled() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of("error", "manual_entry_disabled"));
  }

  /**
   * {@code {"occ","strategy_id","qty","quoted_ask","quoted_at","idempotency_key"}} — the /live
   * confirm-step body. {@code idempotency_key} is minted by the client when the confirm step OPENS
   * (not on click), so a double-click submits one entry, not two.
   */
  public record ManualEntryPayload(
      String occ,
      @JsonProperty("strategy_id") String strategyId,
      Integer qty,
      @JsonProperty("quoted_ask") BigDecimal quotedAsk,
      @JsonProperty("quoted_at") String quotedAt,
      @JsonProperty("idempotency_key") String idempotencyKey) {}
}
