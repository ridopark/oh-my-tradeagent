package com.ohmytradeagent.tdbff.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer guards for the tenant self-service account daily-loss kill-switch reset. Enforces
 * tenant isolation (fail-closed on a missing {@code X-Tenant-Id}), the 15-minute circuit-breaker
 * gate, and the threshold-immutable single-operator reset payload. The reset write flag is ON here
 * so the guard paths are exercised; the dark-launch (flag off → 404) case is in {@link
 * AccountKillSwitchResetDarkLaunchTest}.
 */
@WebMvcTest(AccountKillSwitchController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(properties = "account-killswitch.reset.write-enabled=true")
class AccountKillSwitchControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private WorkflowClient client;
  private WorkflowStub stub;

  @BeforeEach
  void setUp() {
    stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(anyString())).thenReturn(stub);
  }

  private static KillSwitchState state(boolean tripped, OffsetDateTime trippedAt) {
    KillSwitchState s = new KillSwitchState();
    s.setSchemaVersion(1L);
    s.setTripped(tripped);
    s.setReason(tripped ? "auto:daily_loss" : "");
    s.setActor(tripped ? "auto:daily_loss" : "");
    s.setTrippedAt(trippedAt);
    return s;
  }

  private void whenState(KillSwitchState s) {
    when(stub.query(eq("account_killswitch_state"), eq(KillSwitchState.class))).thenReturn(s);
  }

  @Test
  void missingTenantHeader_returns401_get() throws Exception {
    mvc.perform(get("/api/account-killswitch"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("missing_tenant"));
  }

  @Test
  void missingTenantHeader_returns401_post() throws Exception {
    mvc.perform(
            post("/api/account-killswitch/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("missing_tenant"));
    // No workflow addressed at all when the tenant is unresolved.
    verify(stub, never()).update(any(), any(), any());
  }

  @Test
  void state_mapsQueryAndComputesResettable() throws Exception {
    // Tripped 16 minutes ago: past the 15-min circuit breaker → resettable=true.
    OffsetDateTime trippedAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(16);
    String expectedResettableAt = trippedAt.plusSeconds(900).toString();
    // #591: the open exposure cached by the account cap flows through the GET so the reset UI can
    // show what the operator is still holding before they resume.
    KillSwitchState tripped = state(true, trippedAt);
    tripped.setOpenPositions(2L);
    tripped.setOpenMtm(new BigDecimal("1496.00"));
    whenState(tripped);

    mvc.perform(get("/api/account-killswitch").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tripped").value(true))
        .andExpect(jsonPath("$.trippedAt").value(trippedAt.toString()))
        .andExpect(jsonPath("$.resettableAt").value(expectedResettableAt))
        .andExpect(jsonPath("$.openPositions").value(2))
        .andExpect(jsonPath("$.openMtm").value(1496.00));

    // Tripped 5 minutes ago: still inside the circuit breaker (resettableAt in the future).
    // Exposure
    // not cached (null) → the two fields serialize as null (per-strategy switch /
    // pre-first-heartbeat
    // account switch) and the reset UI renders no exposure line.
    OffsetDateTime recent = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
    whenState(state(true, recent));

    mvc.perform(get("/api/account-killswitch").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tripped").value(true))
        .andExpect(jsonPath("$.resettableAt").value(recent.plusSeconds(900).toString()))
        .andExpect(jsonPath("$.openPositions").value(nullValue()))
        .andExpect(jsonPath("$.openMtm").value(nullValue()));
  }

  @Test
  void reset_afterCircuitBreaker_issuesUpdateForAuthenticatedTenant() throws Exception {
    whenState(state(true, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(16)));

    mvc.perform(
            post("/api/account-killswitch/reset")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"loss recovered\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RESET"))
        .andExpect(jsonPath("$.tenant_id").value("acme"));

    ArgumentCaptor<ResetKillSwitchRequest> cap =
        ArgumentCaptor.forClass(ResetKillSwitchRequest.class);
    verify(stub).update(eq("reset_account_killswitch"), eq(Void.class), cap.capture());
    ResetKillSwitchRequest sent = cap.getValue();
    // Audit attribution: the honored reset audits approver_id_1="tenant:<tenant>" +
    // via=manual_reset.
    assertThat(sent.getApproverId1()).isEqualTo("tenant:acme");
    assertThat(sent.getNote()).isEqualTo("loss recovered");
    assertThat(sent.getSchemaVersion()).isEqualTo(1L);
    // Threshold immutability: the reset payload on the wire carries ONLY schema_version /
    // approver_id_1 / note — there is no threshold/sizing field a tenant could smuggle through the
    // reset. Assert on the serialized JSON (what is actually sent) so the guard is immune to
    // bytecode instrumentation (e.g. JaCoCo's synthetic $jacocoData field).
    @SuppressWarnings("unchecked")
    Map<String, Object> wire =
        new com.fasterxml.jackson.databind.ObjectMapper().convertValue(sent, Map.class);
    assertThat(wire.keySet()).containsExactlyInAnyOrder("schema_version", "approver_id_1", "note");
  }

  @Test
  void reset_withinCircuitBreaker_returns409_circuitBreakerActive() throws Exception {
    whenState(state(true, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5)));

    mvc.perform(
            post("/api/account-killswitch/reset")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("circuit_breaker_active"))
        .andExpect(jsonPath("$.resettableAt").exists());

    verify(stub, never()).update(any(), any(), any());
  }

  @Test
  void reset_whenNotTripped_returns409_notTripped() throws Exception {
    whenState(state(false, null));

    mvc.perform(
            post("/api/account-killswitch/reset")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("not_tripped"));

    verify(stub, never()).update(any(), any(), any());
  }

  @Test
  void reset_trippedButNullTrippedAt_failsClosed_returns409() throws Exception {
    // Fail-closed: a tripped switch we can't prove the circuit-breaker window on (null tripped_at,
    // a shouldn't-happen state) refuses rather than allowing an immediate un-halt. No update
    // issued.
    whenState(state(true, null));

    mvc.perform(
            post("/api/account-killswitch/reset")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("circuit_breaker_active"));

    verify(stub, never()).update(any(), any(), any());
  }

  @Test
  void reset_addressesTenantsOwnWorkflowId() throws Exception {
    whenState(state(true, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(16)));

    mvc.perform(
            post("/api/account-killswitch/reset")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());

    // Tenant A can only ever address ITS OWN account kill-switch workflow id — server-derived,
    // never a client parameter.
    ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
    verify(client, org.mockito.Mockito.atLeastOnce()).newUntypedWorkflowStub(id.capture());
    assertThat(id.getValue()).isEqualTo(WorkflowIds.accountKillswitch("acme"));
  }
}
