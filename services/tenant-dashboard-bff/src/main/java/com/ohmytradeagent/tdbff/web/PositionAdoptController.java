package com.ohmytradeagent.tdbff.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ohmytradeagent.contract.AdoptionResult;
import com.ohmytradeagent.contract.AdoptionWorkflowInput;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.tdbff.live.OpenOccCache;
import com.ohmytradeagent.tdbff.platform.StrategyConfigReader;
import com.ohmytradeagent.tdbff.portfolio.PortfolioCache;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/positions/adopt} — the /live "Adopt" button: hand a broker-held lot that nothing
 * manages (typically bought directly at the broker) to a PositionWorkflow, so the operator controls
 * and the account cap cover it.
 *
 * <p>Starts the orchestrator's {@code AdoptionWorkflow} with {@code allow_unrecorded=true} and
 * waits briefly for its {@link AdoptionResult}. The workflow re-reads broker truth and refuses
 * anything already owned, a condor leg, a lot with a SELL already working, or an expired contract;
 * this controller only gates who may ask. Its own class (not {@link PositionsController}) so the
 * existing position-write test slices need no new beans.
 *
 * <p>Dark by default ({@code positions.adopt.write-enabled}): adoption places no order itself, but
 * the owner it starts will sell on its expiry-day timers, so it is armed like the other real-money
 * writes.
 */
@RestController
@RequestMapping("/api/positions")
public class PositionAdoptController {

  private static final Logger log = LoggerFactory.getLogger(PositionAdoptController.class);

  private static final String ADOPTION_WORKFLOW_TYPE = "AdoptionWorkflow";

  /** Compact or padded OCC: root, then YYMMDD, C/P, 8-digit strike. */
  private static final Pattern OCC = Pattern.compile("^[A-Z0-9]{1,6} *\\d{6}[CP]\\d{8}$");

  // Adoption is a handful of activity calls; a healthy one answers in well under this. Kept under
  // the dashboard's 12s BFF fetch timeout so a slow one reads as PENDING, not as a failed request;
  // the workflow keeps running and the page's next refresh shows the outcome.
  private static final long RESULT_TIMEOUT_SECONDS = 8;

  private final TenantContext ctx;
  private final WorkflowClient client;
  private final StrategyConfigReader strategyConfigs;
  private final PortfolioCache portfolioCache;
  private final OpenOccCache openOccCache;
  private final String orchestratorTaskQueue;
  private final boolean writeEnabled;

  public PositionAdoptController(
      TenantContext ctx,
      WorkflowClient client,
      StrategyConfigReader strategyConfigs,
      PortfolioCache portfolioCache,
      OpenOccCache openOccCache,
      @Value("${temporal.orchestrator-task-queue:orchestrator-core}") String orchestratorTaskQueue,
      @Value("${positions.adopt.write-enabled:false}") boolean writeEnabled) {
    this.ctx = ctx;
    this.client = client;
    this.strategyConfigs = strategyConfigs;
    this.portfolioCache = portfolioCache;
    this.openOccCache = openOccCache;
    this.orchestratorTaskQueue = orchestratorTaskQueue;
    this.writeEnabled = writeEnabled;
  }

  @PostMapping("/adopt")
  public ResponseEntity<Map<String, Object>> adopt(
      HttpServletRequest req, @RequestBody(required = false) AdoptPayload body) {
    if (!writeEnabled) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "adopt_disabled"));
    }
    String tenant = ctx.tenantId(req); // fail-closed 401 — the tenant is NEVER a client parameter
    String occ = body == null || body.occ() == null ? "" : body.occ().trim().toUpperCase();
    String strategyId = body == null || body.strategyId() == null ? "" : body.strategyId().trim();
    if (!OCC.matcher(occ).matches()) {
      throw new IllegalArgumentException("occ must be an OCC option symbol");
    }
    if (strategyId.isEmpty()) {
      throw new IllegalArgumentException("strategy_id is required");
    }
    // The owner is started under this strategy's config (exits, timers, broker target), so it must
    // be one the tenant actually owns — and the workflow id stays inside the tenant's namespace.
    boolean owns =
        strategyConfigs.configsForTenant(tenant).stream()
            .anyMatch(c -> strategyId.equals(c.get("strategy_id")));
    if (!owns) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "unknown_strategy"));
    }

    AdoptionWorkflowInput in = new AdoptionWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId(tenant);
    in.setStrategyId(strategyId);
    in.setOcc(occ);
    in.setOperatorId(WorkflowWriteGuards.operatorId(req, tenant));
    in.setAllowUnrecorded(true);

    String workflowId = WorkflowIds.adoption(tenant, strategyId, occ);
    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setTaskQueue(orchestratorTaskQueue)
            .setWorkflowId(workflowId)
            // A running adoption for the OCC rejects the duplicate (a double-click attaches to it
            // below). A COMPLETED one may run again — an earlier refusal must not stick forever,
            // and the workflow's own owner checks make a repeat after ADOPTED an ALREADY_OWNED.
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .build();
    WorkflowStub stub = client.newUntypedWorkflowStub(ADOPTION_WORKFLOW_TYPE, opts);
    try {
      stub.start(in);
    } catch (WorkflowExecutionAlreadyStarted inFlight) {
      stub = client.newUntypedWorkflowStub(workflowId);
    }
    AdoptionResult result;
    try {
      result = stub.getResult(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS, AdoptionResult.class);
    } catch (TimeoutException slow) {
      // Never cancel: the owner may already be started, and cancelling between its start and the
      // fill forward is the one way to strand it. The next page refresh shows the outcome.
      log.warn("adoption still running tenant={} occ={} wf={}", tenant, occ, workflowId);
      return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("outcome", "PENDING"));
    }
    if (result.getOutcome() == AdoptionResult.Outcome.ADOPTED) {
      portfolioCache.invalidate(tenant);
      openOccCache.invalidate(tenant);
    }
    log.info(
        "adoption tenant={} strategy={} occ={} outcome={} reason={}",
        tenant,
        strategyId,
        occ,
        result.getOutcome(),
        result.getReason());

    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("outcome", result.getOutcome().value());
    resp.put("reason", result.getReason());
    resp.put("workflow_id", result.getWorkflowId());
    resp.put("qty", result.getQty());
    HttpStatus status =
        switch (result.getOutcome()) {
          case ADOPTED, ALREADY_OWNED -> HttpStatus.OK;
          case REFUSED_NOT_HELD, REFUSED_NO_ANCHOR, REFUSED_UNSAFE -> HttpStatus.CONFLICT;
        };
    return ResponseEntity.status(status).body(resp);
  }

  public record AdoptPayload(
      @JsonProperty("occ") String occ, @JsonProperty("strategy_id") String strategyId) {}
}
