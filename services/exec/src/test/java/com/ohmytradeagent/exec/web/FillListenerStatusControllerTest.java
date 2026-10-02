package com.ohmytradeagent.exec.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.exec.fill.AlpacaTradeUpdatesStream;
import com.ohmytradeagent.exec.fill.AlpacaTradeUpdatesStream.TenantSocketStatus;
import com.ohmytradeagent.exec.fill.FillListenerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * live-realtime-holdings P2: {@code GET /status/fill-listener}. Pins the locked wire shape, the
 * bean-absent case (listener disabled → {@code enabled:false, tenants:[]}), and that the route is
 * reachable WITHOUT the exec admin token while {@code /internal/broker-credentials} stays gated —
 * the properties below activate {@link ExecAdminTokenFilter} exactly as on a prod alpaca pod.
 */
class FillListenerStatusControllerTest {

  @TestConfiguration
  static class MetricsConfig {
    @Bean
    FillListenerMetrics fillListenerMetrics() {
      return new FillListenerMetrics(new SimpleMeterRegistry());
    }
  }

  /** Listener bean absent (exec.fill-listener.enabled=false, the default). */
  @Nested
  @WebMvcTest(
      controllers = FillListenerStatusController.class,
      properties = {
        "broker.impl=alpaca-paper",
        "broker.creds.source=db",
        "exec.admin.service-token=s3cr3t"
      })
  @Import(MetricsConfig.class)
  class ListenerAbsent {

    @Autowired MockMvc mvc;

    @Test
    void reportsDisabledWithNoTenants() throws Exception {
      mvc.perform(get("/status/fill-listener"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.enabled").value(false))
          .andExpect(jsonPath("$.now").isString())
          .andExpect(jsonPath("$.tenants").isArray())
          .andExpect(jsonPath("$.tenants").isEmpty());
    }

    @Test
    void credentialRouteStaysGatedInTheSameContext() throws Exception {
      // Proves the admin-token filter IS active here, so the 200 above is "not gated", not
      // "filter missing".
      mvc.perform(get("/internal/broker-credentials/acme/account"))
          .andExpect(status().isUnauthorized());
    }
  }

  /** Listener bean present: one row per runner, pod-scoped metrics. */
  @Nested
  @WebMvcTest(
      controllers = FillListenerStatusController.class,
      properties = {
        "broker.impl=alpaca-paper",
        "broker.creds.source=db",
        "exec.admin.service-token=s3cr3t"
      })
  @Import(MetricsConfig.class)
  @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD) // fresh counters
  class ListenerPresent {

    @Autowired MockMvc mvc;
    @Autowired FillListenerMetrics metrics;
    @MockitoBean AlpacaTradeUpdatesStream stream;

    @Test
    void beforeAnyConfirmationOrEvent() throws Exception {
      when(stream.socketStatus())
          .thenReturn(
              List.of(
                  new TenantSocketStatus("alice", true, false),
                  new TenantSocketStatus("bob", false, false)));

      mvc.perform(get("/status/fill-listener")) // no Authorization header
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.enabled").value(true))
          .andExpect(jsonPath("$.now").isString())
          .andExpect(jsonPath("$.tenants.length()").value(2))
          .andExpect(jsonPath("$.tenants[0].tenant_id").value("alice"))
          .andExpect(jsonPath("$.tenants[0].connected").value(true))
          .andExpect(jsonPath("$.tenants[0].subscription_confirmed").value(false))
          .andExpect(jsonPath("$.tenants[0].last_event_age_s").value((Object) null))
          .andExpect(jsonPath("$.tenants[0].reconnects").value(0))
          .andExpect(jsonPath("$.tenants[0].metrics_scope").value("pod"))
          .andExpect(jsonPath("$.tenants[0].subscription_scope").value("socket"))
          .andExpect(jsonPath("$.tenants[1].tenant_id").value("bob"))
          .andExpect(jsonPath("$.tenants[1].connected").value(false));
    }

    @Test
    void afterConfirmationReconnectAndEvent() throws Exception {
      when(stream.socketStatus())
          .thenReturn(List.of(new TenantSocketStatus("pod-wide", true, true)));
      metrics.recordSubscriptionConfirmed();
      metrics.recordReconnect();
      metrics.recordReconnect();
      metrics.markEvent();

      mvc.perform(get("/status/fill-listener"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.tenants[0].tenant_id").value("pod-wide"))
          .andExpect(jsonPath("$.tenants[0].subscription_confirmed").value(true))
          .andExpect(jsonPath("$.tenants[0].subscription_scope").value("socket"))
          .andExpect(jsonPath("$.tenants[0].reconnects").value(2))
          .andExpect(jsonPath("$.tenants[0].last_event_age_s").isNumber())
          .andExpect(jsonPath("$.tenants[0].metrics_scope").value("pod"));
    }

    /**
     * subscription_confirmed is per socket: the pod-wide counter having fired (some OTHER socket
     * acked) must not make an unacked tenant's row read confirmed — the reason the /live Broker
     * light could never go green on a multi-tenant pod.
     */
    @Test
    void subscriptionConfirmedIsPerSocketNotPodWide() throws Exception {
      when(stream.socketStatus())
          .thenReturn(
              List.of(
                  new TenantSocketStatus("alice", true, true),
                  new TenantSocketStatus("bob", true, false)));
      metrics.recordSubscriptionConfirmed(); // alice's ack: pod-wide count > 0

      mvc.perform(get("/status/fill-listener"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.tenants[0].tenant_id").value("alice"))
          .andExpect(jsonPath("$.tenants[0].subscription_confirmed").value(true))
          .andExpect(jsonPath("$.tenants[0].subscription_scope").value("socket"))
          .andExpect(jsonPath("$.tenants[1].tenant_id").value("bob"))
          .andExpect(jsonPath("$.tenants[1].subscription_confirmed").value(false))
          .andExpect(jsonPath("$.tenants[1].subscription_scope").value("socket"));
    }
  }
}
