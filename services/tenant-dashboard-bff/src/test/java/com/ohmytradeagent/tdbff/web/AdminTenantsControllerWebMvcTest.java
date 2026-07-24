package com.ohmytradeagent.tdbff.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.credentials.AdminTenantAccountReader;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader.TenantStrategyBrokerTarget;
import com.ohmytradeagent.tdbff.platform.LivePromotionStateReader;
import com.ohmytradeagent.tdbff.platform.LivePromotionStateReader.LivePromotionState;
import com.ohmytradeagent.tdbff.platform.LivePromotionStateReader.State;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Web-layer contract for the dark-gated, operator-scoped {@code GET /api/admin/tenants}: the
 * per-(tenant,strategy) envelope, masked account, paper/live badge, live activation_state +
 * expires_at, the {@code X-Operator-Id} 400, and a hard no-secret-egress guard. The flag-OFF → 404
 * case lives in {@link AdminTenantsControllerDisabledWebMvcTest} (the bean must be absent there).
 */
@WebMvcTest(AdminTenantsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(
    properties = {"operator.admin-read.enabled=true", "operator.allowlist=ridopark"})
class AdminTenantsControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private DbStrategyConfigReader strategyConfigReader;
  @MockitoBean private AdminTenantAccountReader accountReader;
  @MockitoBean private LivePromotionStateReader livePromotionStateReader;

  @Test
  void missingOperatorHeaderIs400() throws Exception {
    when(strategyConfigReader.listAll()).thenReturn(List.of());

    mvc.perform(get("/api/admin/tenants"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("missing_operator"));
  }

  @Test
  void nonAllowlistedOperatorIs403_generic_noTenantDataEchoed() throws Exception {
    // A well-formed but non-allowlisted operator is rejected 403 BEFORE any tenant data is read or
    // the operator_id is echoed; the readers must not be touched and the body is generic.
    mvc.perform(get("/api/admin/tenants").header("X-Operator-Id", "intruder"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("forbidden"))
        .andExpect(jsonPath("$.operator_id").doesNotExist());
    org.mockito.Mockito.verifyNoInteractions(strategyConfigReader);
  }

  @Test
  void listsOneItemPerStrategy_paperAndLive() throws Exception {
    when(strategyConfigReader.listAll())
        .thenReturn(
            List.of(
                new TenantStrategyBrokerTarget("acme", "s-paper", "alpaca-paper", true),
                // s-live is ACTIVATED (promotion VALID below) but DISABLED — the "looks live but
                // isn't armed" case; enabled must surface false so the UI shows the truth.
                new TenantStrategyBrokerTarget("acme", "s-live", "alpaca-live", false)));
    when(accountReader.accountId("acme", "alpaca-paper")).thenReturn("PA000PAPER1234");
    when(accountReader.accountId("acme", "alpaca-live")).thenReturn("847309116");

    OffsetDateTime expires = OffsetDateTime.parse("2026-07-28T14:30:15Z");
    when(livePromotionStateReader.stateOf(
            ArgumentMatchers.eq("acme"),
            ArgumentMatchers.eq("s-live"),
            ArgumentMatchers.eq("alpaca-live"),
            ArgumentMatchers.any(OffsetDateTime.class)))
        .thenReturn(new LivePromotionState(State.VALID, expires, false));

    mvc.perform(get("/api/admin/tenants").header("X-Operator-Id", "ridopark"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operator_id").value("ridopark"))
        .andExpect(jsonPath("$.count").value(2))
        // paper item
        .andExpect(jsonPath("$.items[0].tenant_id").value("acme"))
        .andExpect(jsonPath("$.items[0].strategy_id").value("s-paper"))
        .andExpect(jsonPath("$.items[0].broker_target").value("alpaca-paper"))
        .andExpect(jsonPath("$.items[0].mode").value("paper"))
        .andExpect(jsonPath("$.items[0].enabled").value(true))
        .andExpect(jsonPath("$.items[0].account_masked").value("••••1234"))
        .andExpect(jsonPath("$.items[0].activation_state").value("n/a"))
        .andExpect(jsonPath("$.items[0].expires_at").doesNotExist())
        .andExpect(jsonPath("$.items[0].at_risk").value(false))
        // live item
        .andExpect(jsonPath("$.items[1].strategy_id").value("s-live"))
        .andExpect(jsonPath("$.items[1].mode").value("live"))
        // Activated (VALID) yet DISABLED — the truth the UI must not hide behind the activation
        // badge.
        .andExpect(jsonPath("$.items[1].enabled").value(false))
        .andExpect(jsonPath("$.items[1].account_masked").value("••••9116"))
        .andExpect(jsonPath("$.items[1].activation_state").value("VALID"))
        .andExpect(jsonPath("$.items[1].expires_at").value("2026-07-28T14:30:15Z"))
        .andExpect(jsonPath("$.items[1].at_risk").value(false));
  }

  @Test
  void liveStaleItemHasStateButNoExpiresAt() throws Exception {
    when(strategyConfigReader.listAll())
        .thenReturn(List.of(new TenantStrategyBrokerTarget("acme", "s-live", "alpaca-live", true)));
    when(accountReader.accountId("acme", "alpaca-live")).thenReturn("847309116");
    when(livePromotionStateReader.stateOf(
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.any(OffsetDateTime.class)))
        .thenReturn(
            new LivePromotionState(
                State.STALE, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), false));

    mvc.perform(get("/api/admin/tenants").header("X-Operator-Id", "ridopark"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].activation_state").value("STALE"))
        // STALE carries no live "valid until": expires_at is null (serialized absent).
        .andExpect(jsonPath("$.items[0].expires_at").doesNotExist());
  }

  @Test
  void shortOrNullAccountMasksToDotsOnly() throws Exception {
    when(strategyConfigReader.listAll())
        .thenReturn(
            List.of(
                new TenantStrategyBrokerTarget("acme", "s-short", "alpaca-paper", true),
                new TenantStrategyBrokerTarget("acme", "s-null", "alpaca-paper", true)));
    when(accountReader.accountId("acme", "alpaca-paper")).thenReturn("12", null);

    mvc.perform(get("/api/admin/tenants").header("X-Operator-Id", "ridopark"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].account_masked").value("••••"))
        .andExpect(jsonPath("$.items[1].account_masked").value("••••"));
  }

  @Test
  void responseNeverContainsSecretColumnNamesOrFullAccount() throws Exception {
    when(strategyConfigReader.listAll())
        .thenReturn(List.of(new TenantStrategyBrokerTarget("acme", "s-live", "alpaca-live", true)));
    when(accountReader.accountId("acme", "alpaca-live")).thenReturn("847309116");
    when(livePromotionStateReader.stateOf(
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.any(OffsetDateTime.class)))
        .thenReturn(
            new LivePromotionState(
                State.VALID, OffsetDateTime.parse("2026-07-28T00:00:00Z"), false));

    MvcResult result =
        mvc.perform(get("/api/admin/tenants").header("X-Operator-Id", "ridopark"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andReturn();

    String json = result.getResponse().getContentAsString();
    for (String secret :
        List.of(
            "ciphertext", "wrapped_dek", "iv", "dek_iv", "kek_version", "api_secret", "api_key")) {
      Assertions.assertThat(json)
          .as("admin tenants response must not leak secret column %s", secret)
          .doesNotContain("\"" + secret + "\"");
    }
    // The full account number must never appear — only the masked last-4.
    Assertions.assertThat(json)
        .as("full broker account must not appear; only the masked last-4")
        .doesNotContain("847309116");
    Assertions.assertThat(json).contains("••••9116");
  }
}
