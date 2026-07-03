package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.invites.InviteWriterRepository;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login-bind: {@code POST /internal/provisioning/bind}. The dashboard's signIn calls this on an
 * UNPROVISIONED first login with the person's TRUSTED server-side OAuth profile {@code (provider,
 * subject, email)} (never a browser-supplied value). If an open, non-expired invite matches the
 * provider-verified email, the identity is bound into {@code dashboard_user} for that invite's
 * tenant and the invite is consumed. Returns the tenants granted (possibly several, one per
 * matching invite across tenants), or an empty list when nothing matches.
 *
 * <p>DARK-GATED via {@code @ConditionalOnProperty("dashboard.writer.enabled")}: the bean (and the
 * {@code dashboard_writer} DSL it writes through) exist only on an enabled cluster; otherwise the
 * route 404s. NOT operator/tenant-scoped — this is a SERVICE route, gated only by the always-on
 * bearer {@code ServiceTokenFilter} (the dashboard server is the sole caller).
 *
 * <p>Security invariants: the tenant is taken ONLY from the matched invite — the body carries NO
 * tenant_id and none is trusted; a bound row is member-only (no role column, so it can never confer
 * operator); every deny reason (no invite / expired / already-consumed / wrong email) collapses to
 * the SAME empty-grant response (no membership/existence oracle).
 */
@RestController
@RequestMapping("/internal/provisioning/bind")
@ConditionalOnProperty(name = "dashboard.writer.enabled", havingValue = "true")
public class ProvisioningBindController {

  private final InviteWriterRepository invites;

  public ProvisioningBindController(InviteWriterRepository invites) {
    this.invites = invites;
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> bind(
      @RequestBody(required = false) Map<String, Object> body) {
    String provider = RequestBodies.str(body, "provider");
    String subject = RequestBodies.str(body, "subject");
    String email = RequestBodies.str(body, "email");

    // Missing identity fields → empty grant (never an oracle, never a 4xx that distinguishes
    // cases).
    if (isBlank(provider) || isBlank(subject) || isBlank(email)) {
      return ResponseEntity.ok(Map.of("granted", List.of()));
    }

    List<String> granted = invites.bindMatchingInvites(provider, subject, email);
    return ResponseEntity.ok(Map.of("granted", granted));
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
