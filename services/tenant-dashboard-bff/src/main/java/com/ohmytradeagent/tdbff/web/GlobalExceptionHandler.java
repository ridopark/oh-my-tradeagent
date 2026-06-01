package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.config.BrokerDataSourceRouter.BrokerNotConfiguredException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the small set of BFF exceptions to HTTP status codes. A missing {@code X-Tenant-Id} is a 401
 * (the caller is unauthenticated for any tenant scope — never a `dev` fallback); an unknown {@code
 * broker_target} is a 404; malformed query params are 400.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

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
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("error", "bad_request", "detail", String.valueOf(e.getMessage())));
  }
}
