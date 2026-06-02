package com.ohmytradeagent.tdbff.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.orders.OrdersReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Guards the shared no-`dev`-fallback contract at the web layer for {@code /api/orders}. */
@WebMvcTest(OrdersController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class OrdersControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private OrdersReader reader;

  @Test
  void missingTenantHeaderIs401() throws Exception {
    mvc.perform(get("/api/orders"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("missing_tenant"));
  }

  @Test
  void returnsTenantScopedOrders() throws Exception {
    // Controller passes the default limit (100) when no `limit` param is supplied.
    when(reader.orders("acme", 100))
        .thenReturn(List.of(Map.of("intent_key", "ok1", "state", "FILLED")));

    mvc.perform(get("/api/orders").header("X-Tenant-Id", "acme"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value("acme"))
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.items[0].intent_key").value("ok1"));
  }
}
