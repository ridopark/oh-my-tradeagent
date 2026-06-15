package com.ohmytradeagent.apigateway.web;

import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowUpdateException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates the small set of Temporal-client + auth exceptions used by the controllers into HTTP
 * status codes. Not aiming for completeness — Phase 5 just needs cleanly-mapped 400/404/409 paths
 * for the verifiable success criteria.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(TenantContext.MissingHeaderException.class)
  public ResponseEntity<Map<String, Object>> missingHeader(TenantContext.MissingHeaderException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("error", "missing_header", "header", e.header()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("error", "bad_request", "detail", String.valueOf(e.getMessage())));
  }

  /**
   * Malformed/unparseable request body. The message is DELIBERATELY dropped (no {@code detail}):
   * Jackson's parse-error message can embed a fragment of the source JSON, and on the
   * secret-bearing {@code POST /broker-credentials} route that fragment would be the
   * api-key/secret. Deserialization fails before the controller (and its redacted record {@code
   * toString}) runs, so this coarse handler is the only thing standing between a bad body and an
   * MF-7 leak — keep it detail-free.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> unreadableBody(HttpMessageNotReadableException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "bad_request"));
  }

  @ExceptionHandler(WorkflowNotFoundException.class)
  public ResponseEntity<Map<String, Object>> notFound(WorkflowNotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of("error", "workflow_not_found", "detail", String.valueOf(e.getMessage())));
  }

  @ExceptionHandler(WorkflowUpdateException.class)
  public ResponseEntity<Map<String, Object>> updateRejected(WorkflowUpdateException e) {
    Throwable cause = e.getCause();
    String detail = cause == null ? e.getMessage() : cause.getMessage();
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(Map.of("error", "update_rejected", "detail", String.valueOf(detail)));
  }
}
