package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ohmytradeagent.apigateway.config.ExecTargetProperties;
import com.ohmytradeagent.apigateway.security.CredentialWriteLimiter;
import com.ohmytradeagent.contract.BrokerCredentialAuditRequest;
import com.ohmytradeagent.orchestrator.workflows.BrokerCredentialAuditWorkflow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.client.WorkflowClient;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pipeline slice test for the shared {@link BrokerCredentialForwardService} (was {@code
 * BrokerCredentialControllerTest} before Phase I-1c factored the forward/audit/rate-limit pipeline
 * out of the controller). Exercises the REAL RestClient forward path against a {@link
 * MockWebServer} standing in for exec, a mocked {@link WorkflowClient}, and a fixed {@link Clock}.
 * Pins: status→outcome mapping (200/422/409/500); rate-limit 429 over cap; lockout after a 422
 * streak; the audit workflow started once with the matching outcome and ZERO key material; the
 * forward body omits correlation_id and carries the per-request tenant header; and the MF-7 gate —
 * the secret never appears in any log (TRACE) on success, on the coarse-error branches, OR on the
 * audit-start-failure branch. The tenant-derivation / cross-tenant guard is the controllers'
 * responsibility and is tested in {@link BrokerCredentialControllerTest} / {@link
 * OperatorBrokerCredentialControllerTest}.
 */
class BrokerCredentialForwardServiceTest {

  private static final String TENANT = "acme";
  private static final String ACTOR = "api-gateway:/broker-credentials";
  private static final String API_KEY = "AKMY_SECRET_KEY_ID_12345";
  private static final String API_SECRET = "ssshhh-this-is-the-broker-secret";

  private static final String PAPER_TARGET = "alpaca-paper";
  private static final String LIVE_TARGET = "alpaca-live";

  private MockWebServer exec;
  private String execBaseUrl;
  private BrokerCredentialForwardService service;
  private TenantBrokerTargetResolver brokerTargetResolver;
  private ExecTargetProperties execTargets;
  private WorkflowClient workflowClient;
  private BrokerCredentialAuditWorkflow auditStub;
  private SimpleMeterRegistry meterRegistry;

  private ListAppender<ILoggingEvent> logCapture;
  private Logger rootLogger;

  @BeforeEach
  void setUp() throws IOException {
    exec = new MockWebServer();
    exec.start();
    execBaseUrl = exec.url("/").toString().replaceAll("/$", "");

    RestClient execRestClient =
        RestClient.builder()
            .baseUrl(execBaseUrl)
            .defaultHeader("Authorization", "Bearer exec-admin-token")
            .build();

    // Route by broker_target: the tenant resolves to the paper target, mapped to the exec stand-in.
    // Individual tests re-stub the resolver / swap the targets map to exercise live / unmapped
    // routing and the fail-closed branches.
    brokerTargetResolver = mock(TenantBrokerTargetResolver.class);
    when(brokerTargetResolver.resolve(TENANT)).thenReturn(Optional.of(PAPER_TARGET));
    execTargets = targets(Map.of(PAPER_TARGET, execBaseUrl));

    workflowClient = mock(WorkflowClient.class);
    auditStub = mock(BrokerCredentialAuditWorkflow.class);
    when(workflowClient.newWorkflowStub(
            any(Class.class), any(io.temporal.client.WorkflowOptions.class)))
        .thenReturn(auditStub);

    meterRegistry = new SimpleMeterRegistry();
    Clock fixed = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);

    service =
        new BrokerCredentialForwardService(
            execRestClient,
            brokerTargetResolver,
            execTargets,
            workflowClient,
            fixed,
            limiter(fixed, 10),
            meterRegistry);

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

  private static ExecTargetProperties targets(Map<String, String> map) {
    ExecTargetProperties p = new ExecTargetProperties();
    p.setTargets(map);
    return p;
  }

  private static CredentialWriteLimiter limiter(Clock clock, int ratePerMinute) {
    // Generous lockout settings so only the rate cap is exercised unless a test drives 422s.
    return new CredentialWriteLimiter(
        clock, ratePerMinute, 5, Duration.ofMinutes(10), Duration.ofMinutes(15));
  }

  private static BrokerCredentialForwardRequest body(long expectedVersion) {
    return new BrokerCredentialForwardRequest(
        TENANT,
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
      var resp = service.forward(TENANT, ACTOR, body(0L), false);

      assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(resp.getBody()).containsEntry("version", 7L);
      // The tenant route (includeBrokerAccountId=false) never echoes the account number.
      assertThat(resp.getBody()).doesNotContainKey("broker_account_id");

      BrokerCredentialAuditRequest audit = captureAuditRequest(mocked);
      assertThat(audit.getOutcome()).isEqualTo(BrokerCredentialAuditRequest.Outcome.SAVED);
      assertThat(audit.getChangeType()).isEqualTo(BrokerCredentialAuditRequest.ChangeType.CREATE);
      assertThat(audit.getTenantId()).isEqualTo(TENANT);
      assertThat(audit.getProvider()).isEqualTo("alpaca");
      assertThat(audit.getActor()).isEqualTo(ACTOR);
      assertThat(audit.getCorrelationId()).isEqualTo("corr-123");
      assertThat(audit.getBrokerAccountId()).isEqualTo("PA3FKGPFYPLH");
      assertThat(audit.getCredentialVersion()).isEqualTo(7L);
      assertThat(audit.getKekVersion()).isEqualTo(3L);
    }
    assertNoSecretInLogs();
  }

  @Test
  void includeBrokerAccountId_returnsAccountNumber_forOperatorReadBack() {
    enqueueExec(200, "{\"version\":7,\"kek_version\":3,\"broker_account_id\":\"PA3FKGPFYPLH\"}");
    try (MockedStatic<WorkflowClient> ignored = Mockito.mockStatic(WorkflowClient.class)) {
      var resp = service.forward(TENANT, "operator:ridopark@gmail.com", body(0L), true);
      assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(resp.getBody())
          .containsEntry("version", 7L)
          .containsEntry("broker_account_id", "PA3FKGPFYPLH");
    }
    assertNoSecretInLogs();
  }

  @Test
  void rotateChangeType_whenExpectedVersionNonZero() {
    enqueueExec(200, "{\"version\":8,\"kek_version\":3,\"broker_account_id\":\"PA3FKGPFYPLH\"}");
    try (MockedStatic<WorkflowClient> mocked = Mockito.mockStatic(WorkflowClient.class)) {
      service.forward(TENANT, ACTOR, body(7L), false);
      BrokerCredentialAuditRequest audit = captureAuditRequest(mocked);
      assertThat(audit.getChangeType()).isEqualTo(BrokerCredentialAuditRequest.ChangeType.ROTATE);
    }
  }

  @Test
  void validationReject_422_mapsToValidationOutcome_noSecretLogged() {
    enqueueExec(422, "{\"error\":\"credential_rejected\"}");
    try (MockedStatic<WorkflowClient> mocked = Mockito.mockStatic(WorkflowClient.class)) {
      var resp = service.forward(TENANT, ACTOR, body(0L), false);
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
      var resp = service.forward(TENANT, ACTOR, body(3L), false);
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
      var resp = service.forward(TENANT, ACTOR, body(0L), false);
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
      var resp = service.forward(TENANT, ACTOR, body(0L), false);
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
    Clock deadClock = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    // Route the paper target at the dead server so the absolute-URI POST hits it and faults.
    BrokerCredentialForwardService unreachable =
        new BrokerCredentialForwardService(
            deadClient,
            brokerTargetResolver,
            targets(Map.of(PAPER_TARGET, deadBaseUrl)),
            workflowClient,
            deadClock,
            limiter(deadClock, 10),
            meterRegistry);

    try (MockedStatic<WorkflowClient> mocked = Mockito.mockStatic(WorkflowClient.class)) {
      var resp = unreachable.forward(TENANT, ACTOR, body(0L), false);
      assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
      BrokerCredentialAuditRequest audit = captureAuditRequest(mocked);
      assertThat(audit.getOutcome())
          .isEqualTo(BrokerCredentialAuditRequest.Outcome.REJECTED_PERSIST_ERROR);
    }
    assertNoSecretInLogs();
  }

  @Test
  void rateLimit_over_cap_is429_noForward_noAudit() {
    // Cap = 2 for this service; the 3rd write in the window trips.
    Clock fixed = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    RestClient rc =
        RestClient.builder().baseUrl(exec.url("/").toString().replaceAll("/$", "")).build();
    BrokerCredentialForwardService capped =
        new BrokerCredentialForwardService(
            rc,
            brokerTargetResolver,
            execTargets,
            workflowClient,
            fixed,
            limiter(fixed, 2),
            meterRegistry);

    enqueueExec(200, "{\"version\":1,\"kek_version\":1,\"broker_account_id\":\"x\"}");
    enqueueExec(200, "{\"version\":2,\"kek_version\":1,\"broker_account_id\":\"x\"}");

    try (MockedStatic<WorkflowClient> ignored = Mockito.mockStatic(WorkflowClient.class)) {
      capped.forward(TENANT, ACTOR, body(0L), false);
      capped.forward(TENANT, ACTOR, body(0L), false);
      long forwardsBefore = exec.getRequestCount();
      assertThatResponseStatus(
          () -> capped.forward(TENANT, ACTOR, body(0L), false), HttpStatus.TOO_MANY_REQUESTS);
      // The 429 did NOT forward to exec.
      assertThat(exec.getRequestCount()).isEqualTo(forwardsBefore);
    }
  }

  @Test
  void lockedTenant_after422Streak_is429_withoutForwarding() {
    // Lockout threshold = 3 for this service; 3 validation rejections arm the lockout, and the
    // 4th write is refused (429) BEFORE any forward to exec.
    Clock fixed = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    RestClient rc =
        RestClient.builder().baseUrl(exec.url("/").toString().replaceAll("/$", "")).build();
    CredentialWriteLimiter lockingLimiter =
        new CredentialWriteLimiter(fixed, 1000, 3, Duration.ofMinutes(10), Duration.ofMinutes(15));
    BrokerCredentialForwardService locking =
        new BrokerCredentialForwardService(
            rc,
            brokerTargetResolver,
            execTargets,
            workflowClient,
            fixed,
            lockingLimiter,
            meterRegistry);

    // Three 422s from exec → three REJECTED_VALIDATION outcomes → lockout armed.
    enqueueExec(422, "{\"error\":\"credential_rejected\"}");
    enqueueExec(422, "{\"error\":\"credential_rejected\"}");
    enqueueExec(422, "{\"error\":\"credential_rejected\"}");

    try (MockedStatic<WorkflowClient> ignored = Mockito.mockStatic(WorkflowClient.class)) {
      locking.forward(TENANT, ACTOR, body(0L), false);
      locking.forward(TENANT, ACTOR, body(0L), false);
      locking.forward(TENANT, ACTOR, body(0L), false);
      long forwardsBefore = exec.getRequestCount();

      assertThatResponseStatus(
          () -> locking.forward(TENANT, ACTOR, body(0L), false), HttpStatus.TOO_MANY_REQUESTS);
      // The locked-out write never reached exec.
      assertThat(exec.getRequestCount()).isEqualTo(forwardsBefore);
    }
    assertNoSecretInLogs();
  }

  @Test
  void forwardBodyOmitsCorrelationId_andCarriesPerRequestTenantHeader() throws Exception {
    enqueueExec(200, "{\"version\":1,\"kek_version\":1,\"broker_account_id\":\"x\"}");
    try (MockedStatic<WorkflowClient> ignored = Mockito.mockStatic(WorkflowClient.class)) {
      service.forward(TENANT, ACTOR, body(0L), false);
    }
    RecordedRequest forwarded = exec.takeRequest();
    assertThat(forwarded.getPath()).isEqualTo("/internal/broker-credentials");
    assertThat(forwarded.getHeader("X-Tenant-Id")).isEqualTo(TENANT);
    String sentBody = forwarded.getBody().readUtf8();
    assertThat(sentBody).contains("api_key_id").contains("api_secret_key");
    assertThat(sentBody).doesNotContain("correlation_id");
  }

  @Test
  void liveTenant_routesToLiveExecPod_notPaper() throws Exception {
    // The tenant's stored broker_target is alpaca-live → the write MUST hit the live exec pod,
    // never
    // the paper one (the historical bug: every write went to the shared paper base URL).
    MockWebServer liveExec = new MockWebServer();
    liveExec.start();
    String liveBaseUrl = liveExec.url("/").toString().replaceAll("/$", "");
    liveExec.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"version\":1,\"kek_version\":1,\"broker_account_id\":\"847309116\"}"));

    when(brokerTargetResolver.resolve(TENANT)).thenReturn(Optional.of(LIVE_TARGET));
    RestClient rc = RestClient.builder().baseUrl(execBaseUrl).build();
    Clock fixed = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    BrokerCredentialForwardService live =
        new BrokerCredentialForwardService(
            rc,
            brokerTargetResolver,
            targets(Map.of(PAPER_TARGET, execBaseUrl, LIVE_TARGET, liveBaseUrl)),
            workflowClient,
            fixed,
            limiter(fixed, 10),
            meterRegistry);

    try (MockedStatic<WorkflowClient> ignored = Mockito.mockStatic(WorkflowClient.class)) {
      var resp = live.forward(TENANT, ACTOR, body(0L), false);
      assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    // The live pod received exactly the credential write; the paper pod received nothing.
    assertThat(liveExec.getRequestCount()).isEqualTo(1);
    assertThat(exec.getRequestCount()).isEqualTo(0);
    RecordedRequest forwarded = liveExec.takeRequest();
    assertThat(forwarded.getPath()).isEqualTo("/internal/broker-credentials");
    assertThat(forwarded.getHeader("X-Tenant-Id")).isEqualTo(TENANT);
    liveExec.shutdown();
    assertNoSecretInLogs();
  }

  @Test
  void paperTenant_routesToPaperExecPod_notLive() throws IOException {
    // The tenant's stored broker_target is alpaca-paper → the write hits the paper pod (preserving
    // today's behavior), never the live one.
    MockWebServer liveExec = new MockWebServer();
    liveExec.start();
    String liveBaseUrl = liveExec.url("/").toString().replaceAll("/$", "");
    enqueueExec(200, "{\"version\":1,\"kek_version\":1,\"broker_account_id\":\"PA3FKGPFYPLH\"}");

    when(brokerTargetResolver.resolve(TENANT)).thenReturn(Optional.of(PAPER_TARGET));
    RestClient rc = RestClient.builder().baseUrl(execBaseUrl).build();
    Clock fixed = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    BrokerCredentialForwardService paper =
        new BrokerCredentialForwardService(
            rc,
            brokerTargetResolver,
            targets(Map.of(PAPER_TARGET, execBaseUrl, LIVE_TARGET, liveBaseUrl)),
            workflowClient,
            fixed,
            limiter(fixed, 10),
            meterRegistry);

    try (MockedStatic<WorkflowClient> ignored = Mockito.mockStatic(WorkflowClient.class)) {
      var resp = paper.forward(TENANT, ACTOR, body(0L), false);
      assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    assertThat(exec.getRequestCount()).isEqualTo(1);
    assertThat(liveExec.getRequestCount()).isEqualTo(0);
    liveExec.shutdown();
    assertNoSecretInLogs();
  }

  @Test
  void unresolvableBrokerTarget_failsClosed_noForward_persistError() {
    // No (ambiguous) strategy_config row → the resolver returns empty → the write is refused BEFORE
    // any forward, mapped to the coarse persist-error outcome. No response enqueued: a stray
    // forward
    // would surface as a hang/failure, not a silent pass.
    when(brokerTargetResolver.resolve(TENANT)).thenReturn(Optional.empty());
    try (MockedStatic<WorkflowClient> mocked = Mockito.mockStatic(WorkflowClient.class)) {
      var resp = service.forward(TENANT, ACTOR, body(0L), false);
      assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
      assertThat(resp.getBody()).doesNotContainKey("version");
      assertThat(exec.getRequestCount()).isEqualTo(0);
      BrokerCredentialAuditRequest audit = captureAuditRequest(mocked);
      assertThat(audit.getOutcome())
          .isEqualTo(BrokerCredentialAuditRequest.Outcome.REJECTED_PERSIST_ERROR);
    }
    assertNoSecretInLogs();
  }

  @Test
  void unmappedLiveTarget_failsClosed_neverFallsBackToPaper() {
    // HARD SAFETY: a -live broker_target absent from exec.targets MUST fail closed — it must NEVER
    // fall back to exec.base-url (the paper pod). The setUp map has ONLY alpaca-paper, so
    // alpaca-live
    // is unmapped; assert the paper stand-in receives nothing.
    when(brokerTargetResolver.resolve(TENANT)).thenReturn(Optional.of(LIVE_TARGET));
    try (MockedStatic<WorkflowClient> mocked = Mockito.mockStatic(WorkflowClient.class)) {
      var resp = service.forward(TENANT, ACTOR, body(0L), false);
      assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
      // The (paper) exec stand-in — the only mapped pod — was NOT hit.
      assertThat(exec.getRequestCount()).isEqualTo(0);
      BrokerCredentialAuditRequest audit = captureAuditRequest(mocked);
      assertThat(audit.getOutcome())
          .isEqualTo(BrokerCredentialAuditRequest.Outcome.REJECTED_PERSIST_ERROR);
    }
    assertNoSecretInLogs();
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

      var resp = service.forward(TENANT, ACTOR, body(0L), false);

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
