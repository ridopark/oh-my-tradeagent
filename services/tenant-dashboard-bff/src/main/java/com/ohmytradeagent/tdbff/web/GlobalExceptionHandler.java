package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.config.BrokerDataSourceRouter.BrokerNotConfiguredException;
import com.ohmytradeagent.tdbff.platform.YamlStrategyRegistry.StrategyNotFoundException;
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
    return badRequestResponse("invalid timestamp: " + e.getMessage());
  }

  private static ResponseEntity<Map<String, Object>> badRequestResponse(String detail) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("error", "bad_request", "detail", detail));
  }

  // A tenant/strategy whose YAML is absent from the mounted config is operator misconfiguration,
  // not
  // a server fault. Map it to 404 (instead of letting the catch-all return 500). The exception
  // message carries the absolute file path — log it server-side at WARN, never in the HTTP body.
  @ExceptionHandler(StrategyNotFoundException.class)
  public ResponseEntity<Map<String, Object>> strategyNotFound(StrategyNotFoundException e) {
    log.warn("strategy config not found: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Map.of("error", "strategy_not_configured"));
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
