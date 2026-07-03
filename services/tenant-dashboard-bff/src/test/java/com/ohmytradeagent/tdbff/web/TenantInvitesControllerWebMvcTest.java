package com.ohmytradeagent.tdbff.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ohmytradeagent.tdbff.invites.InviteWriterRepository;
import com.ohmytradeagent.tdbff.invites.InviteWriterRepository.InviteRecord;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import java.time.OffsetDateTime;
import java.util.UUID;
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
 * Web-layer contract for the dark-gated, operator-scoped {@code POST /api/admin/tenant-invites}:
 * the {@code X-Operator-Id} allowlist gate (400 missing / 403 non-allowlisted, resolved BEFORE any
 * body validation or write), email normalization + validation, unknown-tenant rejection, and the
 * success envelope. Flag-OFF → 404 lives in {@link TenantInvitesControllerDisabledWebMvcTest}.
 */
@WebMvcTest(TenantInvitesController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TenantContext.class)
@TestPropertySource(
    properties = {
      "operator.tenant-invite.enabled=true",
      "dashboard.writer.enabled=true",
      "operator.tenant-invite.ttl-days=7",
      "operator.allowlist=ridopark"
    })
class TenantInvitesControllerWebMvcTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private DbStrategyConfigReader strategyConfigReader;
  @MockitoBean private InviteWriterRepository invites;

  @Test
  void missingOperatorHeaderIs400_beforeAnyWrite() throws Exception {
    mvc.perform(
            post("/api/admin/tenant-invites")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@b.com\",\"tenant_id\":\"acme\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("missing_operator"));
    verifyNoInteractions(invites);
    verifyNoInteractions(strategyConfigReader);
  }

  @Test
  void nonAllowlistedOperatorIs403_beforeAnyWrite() throws Exception {
    mvc.perform(
            post("/api/admin/tenant-invites")
                .header("X-Operator-Id", "intruder")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@b.com\",\"tenant_id\":\"acme\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("forbidden"));
    verifyNoInteractions(invites);
    verifyNoInteractions(strategyConfigReader);
  }

  @Test
  void invalidEmailIs400() throws Exception {
    mvc.perform(
            post("/api/admin/tenant-invites")
                .header("X-Operator-Id", "ridopark")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\",\"tenant_id\":\"acme\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_email"));
    verifyNoInteractions(invites);
  }

  @Test
  void unknownTenantIsRejected_noWrite() throws Exception {
    when(strategyConfigReader.tenantExists("ghost")).thenReturn(false);

    mvc.perform(
            post("/api/admin/tenant-invites")
                .header("X-Operator-Id", "ridopark")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@b.com\",\"tenant_id\":\"ghost\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("unknown_tenant"));
    verify(invites, never())
        .createInvite(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void validRequest_createsInvite_emailNormalized_andEchoesNoSecret() throws Exception {
    when(strategyConfigReader.tenantExists("acme")).thenReturn(true);
    UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
    OffsetDateTime expires = OffsetDateTime.parse("2026-07-10T12:00:00Z");
    when(invites.createInvite(eq("foo@bar.com"), eq("acme"), eq("ridopark"), eq(7)))
        .thenReturn(new InviteRecord(id, "acme", "foo@bar.com", expires));

    mvc.perform(
            post("/api/admin/tenant-invites")
                .header("X-Operator-Id", "ridopark")
                .contentType(MediaType.APPLICATION_JSON)
                // Mixed case + surrounding whitespace must be normalized to lower(trim).
                .content("{\"email\":\"  Foo@Bar.COM \",\"tenant_id\":\"acme\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.invite_id").value(id.toString()))
        .andExpect(jsonPath("$.tenant_id").value("acme"))
        .andExpect(jsonPath("$.email").value("foo@bar.com"))
        .andExpect(jsonPath("$.expires_at").value("2026-07-10T12:00Z"));

    // created_by is the authenticated operator; email persisted normalized.
    ArgumentCaptor<String> emailCap = ArgumentCaptor.forClass(String.class);
    verify(invites).createInvite(emailCap.capture(), eq("acme"), eq("ridopark"), eq(7));
    org.assertj.core.api.Assertions.assertThat(emailCap.getValue()).isEqualTo("foo@bar.com");
  }
}
