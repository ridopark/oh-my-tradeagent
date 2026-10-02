package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.apigateway.config.ExecTargetProperties;
import com.ohmytradeagent.apigateway.security.ServiceTokenFilter;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

/**
 * {@code GET /internal/live/fill-listener-status}: routes via the tenant's broker_target →
 * exec.targets (live vs paper), returns only the caller tenant's row, fails soft to a non-2xx
 * {@code status:"unknown"} body, and sits behind the shared service-token bearer.
 */
class LiveFillListenerStatusControllerTest {

  private static final String TOKEN = "svc-token";
  private static final String LIVE = "http://exec-alpaca-live:8080";
  private static final String PAPER = "http://exec-alpaca-paper:8080";
  private static final String EXEC_BODY =
      """
      {"enabled":true,"now":"2026-10-01T14:00:00Z","tenants":[
        {"tenant_id":"other","connected":true,"subscription_confirmed":true,
         "last_event_age_s":1.0,"reconnects":0,"metrics_scope":"pod",
         "subscription_scope":"socket"},
        {"tenant_id":"acme","connected":true,"subscription_confirmed":false,
         "last_event_age_s":4.5,"reconnects":2,"metrics_scope":"pod",
         "subscription_scope":"socket"}]}
      """;

  private final TenantBrokerTargetResolver resolver = mock(TenantBrokerTargetResolver.class);
  private MockRestServiceServer exec;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    exec = MockRestServiceServer.bindTo(builder).build();
    mvc = mvcFor(builder.build());
  }

  private MockMvc mvcFor(RestClient rest) {
    ExecTargetProperties targets = new ExecTargetProperties();
    targets.setTargets(Map.of("alpaca-live", LIVE, "alpaca-paper", PAPER));
    LiveFillListenerStatusController controller =
        new LiveFillListenerStatusController(
            resolver, targets, new TenantContext("dev", "copytrade-v1"), rest);
    return MockMvcBuilders.standaloneSetup(controller)
        .addFilters(new ServiceTokenFilter(TOKEN, new MockEnvironment()))
        .build();
  }

  private ResultActions call(String tenant) throws Exception {
    return mvc.perform(
        get("/internal/live/fill-listener-status")
            .param("tenant", tenant)
            .header("Authorization", "Bearer " + TOKEN));
  }

  @Test
  void liveTenant_routesToLiveExec_andReturnsOnlyItsRow() throws Exception {
    when(resolver.resolve("acme")).thenReturn(Optional.of("alpaca-live"));
    exec.expect(requestTo(LIVE + "/status/fill-listener"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(EXEC_BODY, MediaType.APPLICATION_JSON));

    call("acme")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.now").value("2026-10-01T14:00:00Z"))
        .andExpect(jsonPath("$.broker_target").value("alpaca-live"))
        .andExpect(jsonPath("$.pod_tenant_count").value(2))
        .andExpect(jsonPath("$.tenants.length()").value(1))
        .andExpect(jsonPath("$.tenants[0].tenant_id").value("acme"))
        .andExpect(jsonPath("$.tenants[0].connected").value(true))
        .andExpect(jsonPath("$.tenants[0].subscription_confirmed").value(false))
        .andExpect(jsonPath("$.tenants[0].last_event_age_s").value(4.5))
        .andExpect(jsonPath("$.tenants[0].reconnects").value(2))
        .andExpect(jsonPath("$.tenants[0].metrics_scope").value("pod"))
        .andExpect(jsonPath("$.tenants[0].subscription_scope").value("socket"));
    exec.verify();
  }

  @Test
  void paperTenant_routesToPaperExec() throws Exception {
    when(resolver.resolve("acme")).thenReturn(Optional.of("alpaca-paper"));
    exec.expect(requestTo(PAPER + "/status/fill-listener"))
        .andRespond(withSuccess(EXEC_BODY, MediaType.APPLICATION_JSON));

    call("acme")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.broker_target").value("alpaca-paper"));
    exec.verify();
  }

  @Test
  void tenantWithoutRow_getsEmptyTenants_neverAnotherTenantsRow() throws Exception {
    when(resolver.resolve("ghost")).thenReturn(Optional.of("alpaca-live"));
    exec.expect(requestTo(LIVE + "/status/fill-listener"))
        .andRespond(withSuccess(EXEC_BODY, MediaType.APPLICATION_JSON));

    call("ghost")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenants.length()").value(0))
        .andExpect(jsonPath("$.pod_tenant_count").value(2));
  }

  @Test
  void unresolvedOrUnmappedBrokerTarget_is422Unknown_noExecCall() throws Exception {
    when(resolver.resolve("acme")).thenReturn(Optional.empty());
    call("acme")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status").value("unknown"))
        .andExpect(jsonPath("$.reason").value("broker_target unresolved"));

    when(resolver.resolve("acme")).thenReturn(Optional.of("ibkr-live"));
    call("acme")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status").value("unknown"))
        .andExpect(jsonPath("$.reason").value("broker_target not routable"));
    exec.verify(); // no request was made
  }

  @Test
  void execNon2xx_is502Unknown() throws Exception {
    when(resolver.resolve("acme")).thenReturn(Optional.of("alpaca-live"));
    exec.expect(requestTo(LIVE + "/status/fill-listener")).andRespond(withServerError());

    call("acme")
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.status").value("unknown"))
        .andExpect(jsonPath("$.broker_target").value("alpaca-live"));
  }

  @Test
  void execMalformed_is502Unknown() throws Exception {
    when(resolver.resolve("acme")).thenReturn(Optional.of("alpaca-live"));
    exec.expect(requestTo(LIVE + "/status/fill-listener"))
        .andRespond(withSuccess("{\"tenants\":[]}", MediaType.APPLICATION_JSON));

    call("acme")
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.reason").value("malformed exec status"));
  }

  @Test
  void execUnreachable_is502Unknown() throws Exception {
    // Production constructor (real bounded client); nothing listens on the target → refused.
    ExecTargetProperties unreachable = new ExecTargetProperties();
    unreachable.setTargets(Map.of("alpaca-live", "http://127.0.0.1:1"));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new LiveFillListenerStatusController(
                    resolver, unreachable, new TenantContext("dev", "copytrade-v1")))
            .build();
    when(resolver.resolve("acme")).thenReturn(Optional.of("alpaca-live"));

    mvc.perform(get("/internal/live/fill-listener-status").param("tenant", "acme"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.status").value("unknown"))
        .andExpect(jsonPath("$.reason").value("exec status read failed"));
  }

  @Test
  void invalidTenant_is400() throws Exception {
    call("bad tenant;").andExpect(status().isBadRequest());
  }

  @Test
  void missingOrWrongBearer_is401_beforeAnyRouting() throws Exception {
    mvc.perform(get("/internal/live/fill-listener-status").param("tenant", "acme"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/internal/live/fill-listener-status")
                .param("tenant", "acme")
                .header("Authorization", "Bearer nope"))
        .andExpect(status().isUnauthorized());
    exec.verify();
    assertThat(mockingDetails(resolver).getInvocations()).isEmpty();
  }
}
