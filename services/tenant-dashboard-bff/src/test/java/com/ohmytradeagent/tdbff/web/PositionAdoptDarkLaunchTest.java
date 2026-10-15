package com.ohmytradeagent.tdbff.web;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.live.OpenOccCache;
import com.ohmytradeagent.tdbff.platform.StrategyConfigReader;
import com.ohmytradeagent.tdbff.portfolio.PortfolioCache;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** No flag set: the default is OFF, and the write surface 404s before touching Temporal. */
@WebMvcTest(PositionAdoptController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
class PositionAdoptDarkLaunchTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private WorkflowClient client;
  @MockitoBean private StrategyConfigReader strategyConfigs;
  @MockitoBean private PortfolioCache portfolioCache;
  @MockitoBean private OpenOccCache openOccCache;

  @Test
  void adopt_isOffByDefault() throws Exception {
    mvc.perform(
            post("/api/positions/adopt")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"occ\":\"NVDA261016C00225000\",\"strategy_id\":\"copytrade-v1\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("adopt_disabled"));
    verifyNoInteractions(client, strategyConfigs);
  }
}
