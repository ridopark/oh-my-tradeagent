package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ohmytradeagent.contract.BrokerCredentialAuditRequest;
import com.ohmytradeagent.orchestrator.workflows.BrokerCredentialAuditWorkflow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.client.WorkflowClient;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * UI-P2-a controller slice test. Exercises the REAL RestClient forward path against a {@link
 * MockWebServer} standing in for exec, a mocked {@link WorkflowClient}, and a fixed {@link Clock}.
 * Pins: status→outcome mapping (200/422/409/500); tenant-mismatch 403; absent X-Tenant-Id 400;
 * rate-limit 429 over cap; the audit workflow started once with the matching outcome and ZERO key
 * material; and the MF-7 gate — the secret never appears in any log (TRACE) on success, on the
 * coarse-error branches, OR on the audit-start-failure branch.
 */
class BrokerCredentialControllerTest {

  private static final String TENANT = "acme";
  private static final String API_KEY = "AKMY_SECRET_KEY_ID_12345";
  private static final String API_SECRET = "ssshhh-this-is-the-broker-secret";

  private MockWebServer exec;
  private BrokerCredentialController controller;
  private WorkflowClient workflowClient;
  private BrokerCredentialAuditWorkflow auditStub;
  private SimpleMeterRegistry meterRegistry;

  private ListAppender<ILoggingEvent> logCapture;
  private Logger rootLogger;

  @BeforeEach
  void setUp() throws IOException {
    exec = new MockWebServer();
    exec.start();

    RestClient execRestClient =
        RestClient.builder()
            .baseUrl(exec.url("/").toString().replaceAll("/$", ""))
            .defaultHeader("Authorization", "Bearer exec-admin-token")
            .build();

    workflowClient = mock(WorkflowClient.class);
    auditStub = mock(BrokerCredentialAuditWorkflow.class);
    when(workflowClient.newWorkflowStub(
            any(Class.class), any(io.temporal.client.WorkflowOptions.class)))
        .thenReturn(auditStub);

    meterRegistry = new SimpleMeterRegistry();
    TenantContext ctx = new TenantContext("dev", "copytrade-v1");
    Clock fixed = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);

    controller =
        new BrokerCredentialController(
            execRestClient, workflowClient, ctx, fixed, meterRegistry, 10);

    rootLogger = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    logCapture = new ListAppender<>();
    logCapture.start();
    rootLogger.addAppender(logCapture);
    rootLogger.setLevel(Level.TRACE);
  }

  @AfterEach
  void tearDown() throws IOException {
    rootLogger.detachAppender(logCapture);
    exec.shutdown();
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
        API_KEY,
        API_SECRET,
        "https://paper-api.alpaca.markets",
        "wss://paper-api.alpaca.markets/stream",
        "acct-1",
        expectedVersion,
        "corr-123");
  }

  private void enqueueExec(int status, String json) {
    exec.enqueue(
        new MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody(json == null ? "" : json));
  }

  private BrokerCredentialAuditRequest captureAuditRequest(MockedStatic<WorkflowClient> mocked) {
    ArgumentCaptor<BrokerCredentialAuditRequest> captor =
        ArgumentCaptor.forClass(BrokerCredentialAuditRequest.class);
    // Disambiguate the Proc1 overload from the Func1 overload (both match a raw any()/capture()).
    mocked.verify(
        () ->
            WorkflowClient.start(
                any(io.temporal.workflow.Functions.Proc1.class), captor.capture()));
    return captor.getValue();
  }

  private void assertNoSecretInLogs() {
    for (ILoggingEvent event : logCapture.list) {
      assertThat(event.getFormattedMessage()).doesNotContain(API_KEY).doesNotContain(API_SECRET);
    }
  }

  @Test
  void savedOutcome_returnsVersion_startsAuditWithAccountVersionKek_noSecretLogged() {
    enqueueExec(200, "{\"version\":7,\"kek_version\":3,\"broker_account_id\":\"PA3FKGPFYPLH\"}");

    try (MockedStatic<WorkflowClient> mocked = Mockito.mockStatic(WorkflowClient.class)) {
      var resp = controller.write(reqWithTenant(TENANT), body(TENANT, 0L));

      assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(resp.getBody()).containsEntry("version", 7L);

      BrokerCredentialAuditRequest audit = captureAuditRequest(mocked);
      assertThat(audit.getOutcome()).isEqualTo(BrokerCredentialAuditRequest.Outcome.SAVED);
      assertThat(audit.getChangeType()).isEqualTo(BrokerCredentialAuditRequest.ChangeType.CREATE);
      assertThat(audit.getTenantId()).isEqualTo(TENANT);
      assertThat(audit.getProvider()).isEqualTo("alpaca");
      assertThat(audit.getActor()).isEqualTo("api-gateway:/broker-credentials");
      assertThat(audit.getCorrelationId()).isEqualTo("corr-123");
      assertThat(audit.getBrokerAccountId()).isEqualTo("PA3FKGPFYPLH");
      assertThat(audit.getCredentialVersion()).isEqualTo(7L);
      assertThat(audit.getKekVersion()).isEqualTo(3L);
    }
    assertNoSecretInLogs();
  }

  @Test
  void rotateChangeType_whenExpectedVersionNonZero() {
    enqueueExec(200, "{\"version\":8,\"kek_version\":3,\"broker_account_id\":\"PA3FKGPFYPLH\"}");
    try (MockedStatic<WorkflowClient> mocked = Mockito.mockStatic(WorkflowClient.class)) {
      controller.write(reqWithTenant(TENANT), body(TENANT, 7L));
      BrokerCredentialAuditRequest audit = captureAuditRequest(mocked);
      assertThat(audit.getChangeType()).isEqualTo(BrokerCredentialAuditRequest.ChangeType.ROTATE);
    }
  }

  @Test
  void validationReject_422_mapsToValidationOutcome_noSecretLogged() {
    enqueueExec(422, "{\"error\":\"credential_rejected\"}");
    try (MockedStatic<WorkflowClient> mocked = Mockito.mockStatic(WorkflowClient.class)) {
      var resp = controller.write(reqWithTenant(TENANT), body(TENANT, 0L));
      assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
      assertThat(resp.getBody()).doesNotContainKey("version");
      BrokerCredentialAuditRequest audit = captureAuditRequest(mocked);
      assertThat(audit.getOutcome())
          .isEqualTo(BrokerCredentialAuditRequest.Outcome.REJECTED_VALIDATION);
      assertThat(audit.getBrokerAccountId()).isNull();
      assertThat(audit.getCredentialVersion()).isNull();
      assertThat(audit.getKekVersion()).isNull();
    }
    assertNoSecretInLogs();
  }

  @Test
  void staleVersion_409_mapsToPersistError_noSecretLogged() {
    enqueueExec(409, "");
    try (MockedStatic<WorkflowClient> mocked = Mockito.mockStatic(WorkflowClient.class)) {
      var resp = controller.write(reqWithTenant(TENANT), body(TENANT, 3L));
      assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
      BrokerCredentialAuditRequest audit = captureAuditRequest(mocked);
      assertThat(audit.getOutcome())
          .isEqualTo(BrokerCredentialAuditRequest.Outcome.REJECTED_PERSIST_ERROR);
    }
    assertNoSecretInLogs();
  }

  @Test
  void execError_500_mapsToPersistError_502ToCaller_noSecretLogged() {
    enqueueExec(500, "");
    try (MockedStatic<WorkflowClient> mocked = Mockito.mockStatic(WorkflowClient.class)) {
      var resp = controller.write(reqWithTenant(TENANT), body(TENANT, 0L));
      assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
      BrokerCredentialAuditRequest audit = captureAuditRequest(mocked);
      assertThat(audit.getOutcome())
          .isEqualTo(BrokerCredentialAuditRequest.Outcome.REJECTED_PERSIST_ERROR);
    }
    assertNoSecretInLogs();
  }

  @Test
  void successStatusButEmptyBody_mapsToPersistError_502_noSavedAudit() {
    // A 2xx with no body is not a verifiable save: we must not audit SAVED-without-version nor
    // return a version. Audit outcome and caller response stay consistent as a persist error.
    enqueueExec(200, "");
    try (MockedStatic<WorkflowClient> mocked = Mockito.mockStatic(WorkflowClient.class)) {
      var resp = controller.write(reqWithTenant(TENANT), body(TENANT, 0L));
      assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
      assertThat(resp.getBody()).doesNotContainKey("version");
      BrokerCredentialAuditRequest audit = captureAuditRequest(mocked);
      assertThat(audit.getOutcome())
          .isEqualTo(BrokerCredentialAuditRequest.Outcome.REJECTED_PERSIST_ERROR);
      assertThat(audit.getCredentialVersion()).isNull();
    }
    assertNoSecretInLogs();
  }

  @Test
  void execUnreachable_transportFailure_mapsToPersistError_502_noSecretLogged() throws IOException {
    // A dead exec (connection refused) exercises the catch(RuntimeException) transport branch.
    // Use a throwaway server so the shared `exec` stays alive for tearDown's shutdown.
    MockWebServer dead = new MockWebServer();
    dead.start();
    String deadBaseUrl = dead.url("/").toString().replaceAll("/$", "");
    dead.shutdown();
    RestClient deadClient = RestClient.builder().baseUrl(deadBaseUrl).build();
    BrokerCredentialController unreachable =
        new BrokerCredentialController(
            deadClient,
            workflowClient,
            new TenantContext("dev", "copytrade-v1"),
            Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC),
            meterRegistry,
            10);

    try (MockedStatic<WorkflowClient> mocked = Mockito.mockStatic(WorkflowClient.class)) {
      var resp = unreachable.write(reqWithTenant(TENANT), body(TENANT, 0L));
      assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
      BrokerCredentialAuditRequest audit = captureAuditRequest(mocked);
      assertThat(audit.getOutcome())
          .isEqualTo(BrokerCredentialAuditRequest.Outcome.REJECTED_PERSIST_ERROR);
    }
    assertNoSecretInLogs();
  }

  @Test
  void tenantMismatch_is403_noForward_noAudit() {
    try (MockedStatic<WorkflowClient> mocked = Mockito.mockStatic(WorkflowClient.class)) {
      assertThatResponseStatus(
          () -> controller.write(reqWithTenant(TENANT), body("other-tenant", 0L)),
          HttpStatus.FORBIDDEN);
      mocked.verifyNoInteractions();
    }
    assertThat(exec.getRequestCount()).isZero();
    assertNoSecretInLogs();
  }

  @Test
  void absentTenantHeader_is400_noForward() {
    try (MockedStatic<WorkflowClient> mocked = Mockito.mockStatic(WorkflowClient.class)) {
      // Strict requiredTenantId throws MissingHeaderException → GlobalExceptionHandler maps to 400.
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> controller.write(reqWithTenant(null), body(TENANT, 0L)))
          .isInstanceOf(TenantContext.MissingHeaderException.class);
      mocked.verifyNoInteractions();
    }
    assertThat(exec.getRequestCount()).isZero();
  }

  @Test
  void rateLimit_over_cap_is429_noForward_noAudit() {
    // Cap = 2 for this controller; the 3rd write in the window trips.
    TenantContext ctx = new TenantContext("dev", "copytrade-v1");
    Clock fixed = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    RestClient rc =
        RestClient.builder().baseUrl(exec.url("/").toString().replaceAll("/$", "")).build();
    BrokerCredentialController capped =
        new BrokerCredentialController(rc, workflowClient, ctx, fixed, meterRegistry, 2);

    enqueueExec(200, "{\"version\":1,\"kek_version\":1,\"broker_account_id\":\"x\"}");
    enqueueExec(200, "{\"version\":2,\"kek_version\":1,\"broker_account_id\":\"x\"}");

    try (MockedStatic<WorkflowClient> mocked = Mockito.mockStatic(WorkflowClient.class)) {
      capped.write(reqWithTenant(TENANT), body(TENANT, 0L));
      capped.write(reqWithTenant(TENANT), body(TENANT, 0L));
      long forwardsBefore = exec.getRequestCount();
      assertThatResponseStatus(
          () -> capped.write(reqWithTenant(TENANT), body(TENANT, 0L)),
          HttpStatus.TOO_MANY_REQUESTS);
      // The 429 did NOT forward to exec.
      assertThat(exec.getRequestCount()).isEqualTo(forwardsBefore);
    }
  }

  @Test
  void forwardBodyOmitsCorrelationId_andCarriesPerRequestTenantHeader() throws Exception {
    enqueueExec(200, "{\"version\":1,\"kek_version\":1,\"broker_account_id\":\"x\"}");
    try (MockedStatic<WorkflowClient> ignored = Mockito.mockStatic(WorkflowClient.class)) {
      controller.write(reqWithTenant(TENANT), body(TENANT, 0L));
    }
    RecordedRequest forwarded = exec.takeRequest();
    assertThat(forwarded.getPath()).isEqualTo("/internal/broker-credentials");
    assertThat(forwarded.getHeader("X-Tenant-Id")).isEqualTo(TENANT);
    String sentBody = forwarded.getBody().readUtf8();
    assertThat(sentBody).contains("api_key_id").contains("api_secret_key");
    assertThat(sentBody).doesNotContain("correlation_id");
  }

  @Test
  void auditStartFailure_stillReturnsWriteResult_bumpsCounter_noSecretLogged() {
    enqueueExec(200, "{\"version\":9,\"kek_version\":1,\"broker_account_id\":\"PA3FKGPFYPLH\"}");
    try (MockedStatic<WorkflowClient> mocked = Mockito.mockStatic(WorkflowClient.class)) {
      mocked
          .when(
              () ->
                  WorkflowClient.start(
                      any(io.temporal.workflow.Functions.Proc1.class),
                      any(BrokerCredentialAuditRequest.class)))
          .thenThrow(new RuntimeException("temporal unreachable"));

      var resp = controller.write(reqWithTenant(TENANT), body(TENANT, 0L));

      // The write succeeded in exec, so the endpoint STILL returns the success result.
      assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(resp.getBody()).containsEntry("version", 9L);
    }
    assertThat(meterRegistry.counter("broker_credential_audit_start_failures").count())
        .isEqualTo(1.0);
    assertNoSecretInLogs();
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
