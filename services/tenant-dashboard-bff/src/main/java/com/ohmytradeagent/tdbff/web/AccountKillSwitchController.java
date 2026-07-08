package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant self-service control for the caller's OWN account daily-loss kill switch ({@code
 * WorkflowIds.accountKillswitch(tenant)} — tenant-scoped, strategy-agnostic).
 *
 * <p>The tenant dashboard is the tenant's ONLY path to this control, so the circuit-breaker
 * cooldown (below) and every isolation guard are enforced SERVER-SIDE here, not in the UI. The
 * tenant is resolved fail-closed from {@code X-Tenant-Id} via {@link TenantContext#tenantId} (401
 * on absent/blank/malformed — never a {@code dev} fallback) and the workflow id is derived
 * server-side, so a caller can only ever address its own account switch — never tenant B's.
 *
 * <ul>
 *   <li>{@code GET /api/account-killswitch} — current state + the computed {@code resettableAt}
 *       ({@code trippedAt + 15min}) the UI counts down to.
 *   <li>{@code POST /api/account-killswitch/reset} — single-operator reset (approver_id_1 = {@code
 *       "tenant:" + tenant}), gated by the 15-minute circuit breaker.
 * </ul>
 */
@RestController
@RequestMapping("/api/account-killswitch")
public class AccountKillSwitchController {

  /**
   * Circuit breaker: the tenant must wait this long AFTER the account daily-loss switch trips
   * before a self-service reset is honored. This is a deliberate cool-off — a tenant that just
   * breached its daily loss cap cannot immediately un-halt and keep trading into the same drawdown.
   * Computed off the workflow's {@code tripped_at}; surfaced on the state read ({@code
   * resettableAt}) and enforced on the reset write (409 {@code circuit_breaker_active}).
   */
  static final long CIRCUIT_BREAKER_SECONDS = 900; // 15 minutes

  private static final Logger log = LoggerFactory.getLogger(AccountKillSwitchController.class);

  private final WorkflowClient client;
  private final TenantContext ctx;

  /**
   * Server-side dark-launch gate for the RESET WRITE (default false). Mirrors the dashboard's
   * {@code ACCOUNT_KILLSWITCH_RESET_WRITE_ENABLED} UI flag so the "controlled rollout" is enforced
   * server-side, not just cosmetically on the button: while off, {@code POST /reset} 404s. The GET
   * state read stays ungated so the tenant still sees the tripped banner + countdown.
   */
  private final boolean resetWriteEnabled;

  public AccountKillSwitchController(
      WorkflowClient client,
      TenantContext ctx,
      @Value("${account-killswitch.reset.write-enabled:false}") boolean resetWriteEnabled) {
    this.client = client;
    this.ctx = ctx;
    this.resetWriteEnabled = resetWriteEnabled;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> state(HttpServletRequest req) {
    String tenant = ctx.tenantId(req); // fail-closed 401 on missing/blank/malformed X-Tenant-Id
    String wfId = WorkflowIds.accountKillswitch(tenant);
    try {
      WorkflowStub stub = client.newUntypedWorkflowStub(wfId);
      KillSwitchState s = stub.query("account_killswitch_state", KillSwitchState.class);

      boolean tripped = Boolean.TRUE.equals(s.getTripped());
      OffsetDateTime trippedAt = s.getTrippedAt();
      OffsetDateTime resettableAt =
          (tripped && trippedAt != null) ? trippedAt.plusSeconds(CIRCUIT_BREAKER_SECONDS) : null;

      Map<String, Object> body = new LinkedHashMap<>();
      body.put("tripped", tripped);
      body.put("trippedAt", trippedAt == null ? null : trippedAt.toString());
      body.put("reason", s.getReason());
      body.put("resettableAt", resettableAt == null ? null : resettableAt.toString());
      return ResponseEntity.ok(body);
    } catch (WorkflowNotFoundException e) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
  }

  @PostMapping("/reset")
  public ResponseEntity<Map<String, Object>> reset(
      HttpServletRequest req, @RequestBody(required = false) ResetPayload body) {
    if (!resetWriteEnabled) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build(); // dark-launch: write surface off
    }
    String tenant = ctx.tenantId(req); // fail-closed 401 — the tenant is NEVER a client parameter
    String wfId = WorkflowIds.accountKillswitch(tenant);
    WorkflowStub stub = client.newUntypedWorkflowStub(wfId);

    try {
      // Server-side guard order: state → not-tripped → circuit-breaker → reset.
      KillSwitchState s = stub.query("account_killswitch_state", KillSwitchState.class);
      if (!Boolean.TRUE.equals(s.getTripped())) {
        // Refusals are WARN-logged (no audit yet) so a tenant probing the breaker leaves a trail.
        log.warn("account-killswitch reset refused: not_tripped tenant={}", tenant);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "not_tripped"));
      }

      // Fail-CLOSED: to honor a reset we MUST be able to prove the 15-min circuit breaker elapsed.
      // A tripped switch with a null tripped_at (the workflow always stamps it, so this is a
      // shouldn't-happen state) refuses rather than allowing an immediate un-halt on a real-money
      // cap.
      OffsetDateTime trippedAt = s.getTrippedAt();
      OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
      OffsetDateTime resettableAt =
          trippedAt == null ? null : trippedAt.plusSeconds(CIRCUIT_BREAKER_SECONDS);
      if (resettableAt == null || now.isBefore(resettableAt)) {
        log.warn(
            "account-killswitch reset refused: circuit_breaker_active tenant={} resettable_at={}",
            tenant,
            resettableAt);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("error", "circuit_breaker_active");
        if (resettableAt != null) {
          b.put("resettableAt", resettableAt.toString());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(b);
      }

      // Single-operator, threshold-immutable reset: the DTO carries only schema_version /
      // approver_id_1 / note, so a tenant cannot smuggle a loss-cap threshold through the reset.
      // The
      // honored reset audits (via the workflow) as KillSwitchResetApproved with
      // approver_id_1="tenant:"+tenant.
      ResetKillSwitchRequest rr = new ResetKillSwitchRequest();
      rr.setSchemaVersion(1L);
      rr.setApproverId1("tenant:" + tenant);
      rr.setNote(body == null ? null : body.note());

      // A concurrent race (switch reset/untripped between our query and here) is rejected by the
      // workflow validator; the resulting WorkflowUpdateException maps to 409 via
      // GlobalExceptionHandler.
      stub.update("reset_account_killswitch", Void.class, rr);
      return ResponseEntity.ok(Map.of("status", "RESET", "tenant_id", tenant));
    } catch (WorkflowNotFoundException e) {
      // No account-killswitch workflow for this tenant (never bootstrapped) — parity with GET's
      // 404.
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
  }

  public record ResetPayload(String note) {}
}
