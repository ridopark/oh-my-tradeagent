package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.platform.TenantStrategyResolver;
import com.ohmytradeagent.tdbff.security.DashboardMembershipReader;
import com.ohmytradeagent.tdbff.security.DelegatedAssertionAuthenticator;
import com.ohmytradeagent.tdbff.security.DelegatedAssertionTestKeys;
import com.ohmytradeagent.tdbff.trades.TradesReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Seam C': filter → {@link DelegatedAssertionAuthenticator} → wrapper → {@link TenantContext} →
 * handler, end to end, with {@code bff.mcp-delegated.enabled=true} and the primary test public key
 * configured via {@code @TestPropertySource}.
 *
 * <p>The {@link DashboardMembershipReader} is a mock (the bean exists because {@code
 * bff.mcp-delegated.enabled=true} satisfies the conditional, but its {@code isMember} call is
 * stubbed so no real DB is needed). {@link DelegatedAssertionAuthenticator} is imported explicitly
 * because {@code @WebMvcTest} only loads web-layer beans; it is wired with the mock reader and the
 * test key from application properties.
 *
 * <p>Prior art: {@link McpWhoamiEndToEndWebMvcTest} (Seam C, static MCP token).
 */
@WebMvcTest({McpWhoamiController.class, TradesController.class})
@Import({TenantContext.class, DelegatedAssertionAuthenticator.class})
@TestPropertySource(
    properties = {
      "bff.mcp-delegated.enabled=true",
      "bff.mcp-delegated.public-key=" + DelegatedAssertionTestKeys.PRIMARY_PUBLIC_KEY_B64
    })
class McpDelegatedEndToEndWebMvcTest {

  // sha256("google:sub-1") first 12 hex — computed independently with sha256sum.
  private static final String DLG_CREDENTIAL_ID = "dlg-daf0e67a0269";
  private static final String DLG_SUB = "sub-1";
  private static final String DLG_TENANT = "acme";

  @Autowired private MockMvc mvc;

  /**
   * Mock for DashboardMembershipReader. Its presence satisfies DelegatedAssertionAuthenticator's
   * single-constructor dependency; individual tests stub its return value.
   */
  @MockitoBean private DashboardMembershipReader membershipReader;

  @MockitoBean private TradesReader tradesReader;
  @MockitoBean private TenantStrategyResolver strategyResolver;

  @Test
  void whoami_withDelegatedAssertion_returnsDlgBinding() throws Exception {
    when(membershipReader.isMember("google", DLG_SUB, DLG_TENANT)).thenReturn(true);
    String assertion =
        DelegatedAssertionTestKeys.build(
            DelegatedAssertionTestKeys.validHeader(),
            DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));

    mvc.perform(get("/api/mcp/whoami").header("Authorization", "Bearer " + assertion))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    "{\"tenant_id\":\"acme\",\"token_id\":\""
                        + DLG_CREDENTIAL_ID
                        + "\",\"scope\":\"read\"}",
                    true));
  }

  @Test
  void trades_withDelegatedAssertion_readsAsAcme() throws Exception {
    when(membershipReader.isMember("google", DLG_SUB, DLG_TENANT)).thenReturn(true);
    when(strategyResolver.strategyIdsForTenant(DLG_TENANT)).thenReturn(List.of("s1"));
    when(tradesReader.trades(eq(DLG_TENANT), eq(List.of("s1")), isNull(), anyInt()))
        .thenReturn(List.of(Map.of("event_id", "e1", "kind", "EntryFilled")));
    String assertion =
        DelegatedAssertionTestKeys.build(
            DelegatedAssertionTestKeys.validHeader(),
            DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));

    mvc.perform(get("/api/trades").header("Authorization", "Bearer " + assertion))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenant_id").value(DLG_TENANT));

    // isMember was called with the right arguments — proves the BFF re-checks membership.
    verify(membershipReader).isMember(anyString(), anyString(), eq(DLG_TENANT));
    // TradesReader was called as "acme" — proves the wrapper's tenant injection reached the
    // handler.
    verify(tradesReader).trades(eq(DLG_TENANT), eq(List.of("s1")), isNull(), anyInt());
  }

  @Test
  void whoami_membershipAbsent_is403() throws Exception {
    when(membershipReader.isMember("google", DLG_SUB, DLG_TENANT)).thenReturn(false);
    String assertion =
        DelegatedAssertionTestKeys.build(
            DelegatedAssertionTestKeys.validHeader(),
            DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));

    mvc.perform(get("/api/mcp/whoami").header("Authorization", "Bearer " + assertion))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("forbidden"));
  }

  @Test
  void whoami_post_is403() throws Exception {
    // POST is not GET/HEAD → 403 from the method gate inside applyMcpReadRules.
    when(membershipReader.isMember(anyString(), anyString(), anyString())).thenReturn(true);
    String assertion =
        DelegatedAssertionTestKeys.build(
            DelegatedAssertionTestKeys.validHeader(),
            DelegatedAssertionTestKeys.validClaims(DLG_SUB, DLG_TENANT));

    mvc.perform(post("/api/mcp/whoami").header("Authorization", "Bearer " + assertion))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("forbidden"));
  }

  @Test
  void unknownToken_is401() throws Exception {
    // An unrecognised bearer that is not a compact JWS → NOT_AN_ASSERTION → rule 6 → 401.
    mvc.perform(get("/api/mcp/whoami").header("Authorization", "Bearer not-a-valid-token"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("unauthorized"));
  }
}
