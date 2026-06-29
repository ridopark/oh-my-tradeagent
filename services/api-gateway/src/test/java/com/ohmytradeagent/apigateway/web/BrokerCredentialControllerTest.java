package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * UI-P2-a controller slice test. After Phase I-1c, the forward/audit/rate-limit pipeline lives in
 * {@link BrokerCredentialForwardService} (covered by {@link BrokerCredentialForwardServiceTest}),
 * so this test pins ONLY the controller's own responsibilities: strict tenant resolution (absent
 * X-Tenant-Id → 400, no forward), the cross-tenant guard (body tenant ≠ header tenant → 403, no
 * forward), and that a valid request delegates to the service with the route's fixed actor and
 * {@code includeBrokerAccountId=false} (the tenant route never echoes the account number).
 */
class BrokerCredentialControllerTest {

  private static final String TENANT = "acme";
  private static final String ACTOR = "api-gateway:/broker-credentials";

  private BrokerCredentialForwardService forwardService;
  private BrokerCredentialController controller;

  @BeforeEach
  void setUp() {
    forwardService = mock(BrokerCredentialForwardService.class);
    controller =
        new BrokerCredentialController(forwardService, new TenantContext("dev", "copytrade-v1"));
  }

  private static HttpServletRequest reqWithTenant(String tenant) {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/broker-credentials");
    if (tenant != null) {
      req.addHeader("X-Tenant-Id", tenant);
    }
    return req;
  }

  private static BrokerCredentialForwardRequest body(String tenant, long expectedVersion) {
    return new BrokerCredentialForwardRequest(
        tenant,
        "alpaca",
        "AKMY_KEY",
        "secret",
        "https://paper-api.alpaca.markets",
        "wss://paper-api.alpaca.markets/stream",
        "acct-1",
        expectedVersion,
        "corr-123");
  }

  @Test
  void validRequest_delegatesToService_withFixedActor_andNoAccountEcho() {
    ResponseEntity<Map<String, Object>> ok = ResponseEntity.ok(Map.of("version", 7L));
    when(forwardService.forward(eq(TENANT), eq(ACTOR), any(), eq(false))).thenReturn(ok);

    var resp = controller.write(reqWithTenant(TENANT), body(TENANT, 0L));

    assertThat(resp).isSameAs(ok);
    verify(forwardService).forward(eq(TENANT), eq(ACTOR), any(), eq(false));
  }

  @Test
  void tenantMismatch_is403_noForward() {
    assertThatResponseStatus(
        () -> controller.write(reqWithTenant(TENANT), body("other-tenant", 0L)),
        HttpStatus.FORBIDDEN);
    verifyNoInteractions(forwardService);
  }

  @Test
  void nullBody_is403_noForward() {
    assertThatResponseStatus(
        () -> controller.write(reqWithTenant(TENANT), null), HttpStatus.FORBIDDEN);
    verifyNoInteractions(forwardService);
  }

  @Test
  void absentTenantHeader_is400_noForward() {
    // Strict requiredTenantId throws MissingHeaderException → GlobalExceptionHandler maps to 400.
    assertThatThrownBy(() -> controller.write(reqWithTenant(null), body(TENANT, 0L)))
        .isInstanceOf(TenantContext.MissingHeaderException.class);
    verify(forwardService, never()).forward(any(), any(), any(), anyBoolean());
  }

  private static void assertThatResponseStatus(Runnable r, HttpStatus expected) {
    try {
      r.run();
      org.assertj.core.api.Assertions.fail("expected ResponseStatusException " + expected);
    } catch (ResponseStatusException e) {
      assertThat(e.getStatusCode()).isEqualTo(expected);
    }
  }
}
