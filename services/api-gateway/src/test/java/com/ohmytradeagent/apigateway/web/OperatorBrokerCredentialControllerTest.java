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
 * Phase I-1c operator credential-write controller slice test against a mocked {@link
 * BrokerCredentialForwardService}. Pins the operator route's own responsibilities (the shared
 * pipeline is covered by {@link BrokerCredentialForwardServiceTest}): missing X-Operator-Id → 400
 * (no forward); malformed path tenant → 400 (no forward); body tenant ≠ path tenant → 403 (no
 * forward); a valid request delegates to the service with the path tenant, the {@code operator:}
 * actor, and {@code includeBrokerAccountId=true} (the onboarding read-back). The bearer-missing 401
 * is enforced by ServiceTokenFilter; the flag-off 404 is bean absence (see {@link
 * OperatorCredentialWriteDarkProofTest}).
 */
class OperatorBrokerCredentialControllerTest {

  private static final String TENANT = "acme";
  private static final String OPERATOR = "ridopark@gmail.com";

  private BrokerCredentialForwardService forwardService;
  private OperatorBrokerCredentialController controller;

  @BeforeEach
  void setUp() {
    forwardService = mock(BrokerCredentialForwardService.class);
    controller =
        new OperatorBrokerCredentialController(
            forwardService, new TenantContext("dev", "copytrade-v1"));
  }

  private static HttpServletRequest reqWithOperator(String operator) {
    MockHttpServletRequest req =
        new MockHttpServletRequest("POST", "/admin/tenants/" + TENANT + "/broker-credentials");
    if (operator != null) {
      req.addHeader("X-Operator-Id", operator);
    }
    return req;
  }

  private static BrokerCredentialForwardRequest body(String tenant) {
    return new BrokerCredentialForwardRequest(
        tenant,
        "alpaca",
        "AKMY_KEY",
        "secret",
        "https://paper-api.alpaca.markets",
        "wss://paper-api.alpaca.markets/stream",
        "acct-1",
        0L,
        "corr-123");
  }

  @Test
  void validRequest_delegatesWithPathTenant_operatorActor_andAccountEcho() {
    ResponseEntity<Map<String, Object>> ok =
        ResponseEntity.ok(Map.of("version", 1L, "broker_account_id", "PA3FKGPFYPLH"));
    when(forwardService.forward(eq(TENANT), eq("operator:" + OPERATOR), any(), eq(true)))
        .thenReturn(ok);

    var resp = controller.write(reqWithOperator(OPERATOR), TENANT, body(TENANT));

    assertThat(resp).isSameAs(ok);
    verify(forwardService).forward(eq(TENANT), eq("operator:" + OPERATOR), any(), eq(true));
  }

  @Test
  void missingOperatorHeader_is400_noForward() {
    assertThatThrownBy(() -> controller.write(reqWithOperator(null), TENANT, body(TENANT)))
        .isInstanceOf(TenantContext.MissingHeaderException.class);
    verifyNoInteractions(forwardService);
  }

  @Test
  void malformedPathTenant_is400_noForward() {
    assertThatResponseStatus(
        () -> controller.write(reqWithOperator(OPERATOR), "bad/tenant", body("bad/tenant")),
        HttpStatus.BAD_REQUEST);
    verify(forwardService, never()).forward(any(), any(), any(), anyBoolean());
  }

  @Test
  void bodyTenantMismatch_is403_noForward() {
    assertThatResponseStatus(
        () -> controller.write(reqWithOperator(OPERATOR), TENANT, body("other-tenant")),
        HttpStatus.FORBIDDEN);
    verify(forwardService, never()).forward(any(), any(), any(), anyBoolean());
  }

  @Test
  void nullBody_is403_noForward() {
    assertThatResponseStatus(
        () -> controller.write(reqWithOperator(OPERATOR), TENANT, null), HttpStatus.FORBIDDEN);
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
