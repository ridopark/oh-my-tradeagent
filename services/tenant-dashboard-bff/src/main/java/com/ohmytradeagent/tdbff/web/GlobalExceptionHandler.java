package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.config.BrokerDataSourceRouter.BrokerNotConfiguredException;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowUpdateException;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps the small set of BFF exceptions to HTTP status codes. A missing {@code X-Tenant-Id} is a 401
 * (the caller is unauthenticated for any tenant scope — never a `dev` fallback); an unknown {@code
 * broker_target} is a 404; malformed query params are 400.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(TenantContext.MissingTenantException.class)
  public ResponseEntity<Map<String, Object>> missingTenant(TenantContext.MissingTenantException e) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(Map.of("error", "missing_tenant", "detail", String.valueOf(e.getMessage())));
  }

  @ExceptionHandler(TenantContext.MissingOperatorException.class)
  public ResponseEntity<Map<String, Object>> missingOperator(
      TenantContext.MissingOperatorException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("error", "missing_operator", "detail", String.valueOf(e.getMessage())));
  }

  /**
   * A well-formed but non-allowlisted {@code X-Operator-Id} on the operator-admin route → 403.
   * Deliberately GENERIC body (no operator echo, no reason): the response must not be a membership
   * oracle for the allowlist.
   */
  @ExceptionHandler(TenantContext.UnauthorizedOperatorException.class)
  public ResponseEntity<Map<String, Object>> unauthorizedOperator(
      TenantContext.UnauthorizedOperatorException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
  }

  /**
   * A workflow Update rejected by its validator (e.g. a concurrent account-kill-switch reset means
   * the switch is already {@code not_tripped} by the time our reset lands) surfaces as a 409 — the
   * request lost a race, it is not a server fault. Mirrors the api-gateway handler.
   */
  @ExceptionHandler(WorkflowUpdateException.class)
  public ResponseEntity<Map<String, Object>> updateRejected(WorkflowUpdateException e) {
    Throwable cause = e.getCause();
    String detail = cause == null ? e.getMessage() : cause.getMessage();
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of("error", "update_rejected", "detail", String.valueOf(detail)));
  }

  /**
   * The addressed PositionWorkflow no longer exists — it completed/terminated between the /live
   * render and the operator's "Force exit" click (self-heal, EOD flatten, or an already-cleared
   * phantom — the exact stale-row case this feature targets). Temporal's {@code stub.update} throws
   * {@link WorkflowNotFoundException} for this; without this handler it falls through to the
   * catch-all → 500. Map it to a friendly 409 CONFLICT: the target is already in the closed state,
   * the operator did nothing wrong. (The api-gateway's identical {@code force_close} update relies
   * on the same bare {@code WorkflowNotFoundException}.)
   */
  @ExceptionHandler(WorkflowNotFoundException.class)
  public ResponseEntity<Map<String, Object>> positionAlreadyClosed(WorkflowNotFoundException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of("error", "position_already_closed"));
  }

  @ExceptionHandler(BrokerNotConfiguredException.class)
  public ResponseEntity<Map<String, Object>> brokerNotConfigured(BrokerNotConfiguredException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of("error", "broker_not_configured", "detail", String.valueOf(e.getMessage())));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
    return badRequestResponse(String.valueOf(e.getMessage()));
  }

  // A malformed `since` (e.g. /api/trades?since=not-a-date) reaches OffsetDateTime.parse and throws
  // DateTimeParseException — which extends DateTimeException, NOT IllegalArgumentException, so it
  // would otherwise fall through to a 500. It's bad client input: map it to 400.
  @ExceptionHandler(DateTimeParseException.class)
  public ResponseEntity<Map<String, Object>> badTimestamp(DateTimeParseException e) {
    // Fixed message — don't reflect the caller's raw input back in the response body.
    return badRequestResponse("invalid 'since' timestamp; expected ISO-8601");
  }

  private static ResponseEntity<Map<String, Object>> badRequestResponse(String detail) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("error", "bad_request", "detail", detail));
  }

  // Catch-all for anything not mapped above (and not a framework exception — those keep their
  // proper status via ResponseEntityExceptionHandler). Log server-side; return a generic body so a
  // stack trace / internal detail never reaches the client.
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> internalError(Exception e) {
    log.error("unhandled exception in tenant-dashboard-bff", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("error", "internal_error"));
  }
}
