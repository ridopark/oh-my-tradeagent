package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.credentials.AdminTenantAccountReader;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader.TenantStrategyBrokerTarget;
import com.ohmytradeagent.tdbff.platform.LivePromotionStateReader;
import com.ohmytradeagent.tdbff.platform.LivePromotionStateReader.LivePromotionState;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * I-1a (operator-account-onboarding): {@code GET /api/admin/tenants} — an OPERATOR-scoped,
 * cross-tenant READ listing every (tenant, strategy) with its broker_target, MASKED broker account,
 * paper/live mode, and (for live) the live-promotion activation state + TTL expiry. Pure read; NO
 * writes, NO create-tenant (that is I-1b), NO secret egress.
 *
 * <p>DARK-GATED: the bean exists only when {@code operator.admin-read.enabled=true} (default
 * false). With the flag off the bean is absent and the route 404s — the feature ships dormant.
 *
 * <p>Security: the BFF {@code ServiceTokenFilter} already bearer-gates EVERY non-actuator request
 * (so this endpoint is bearer-gated like all others), and this controller additionally requires the
 * {@code X-Operator-Id} header (400 if absent) — the operator analogue of the tenant routes'
 * required {@code X-Tenant-Id}. It deliberately does NOT call {@code TenantContext.tenantId(req)}:
 * the listing is cross-tenant by design.
 */
@RestController
@RequestMapping("/api/admin/tenants")
@ConditionalOnProperty(name = "operator.admin-read.enabled", havingValue = "true")
public class AdminTenantsController {

  private final DbStrategyConfigReader strategyConfigReader;
  private final AdminTenantAccountReader accountReader;
  private final LivePromotionStateReader livePromotionStateReader;
  private final TenantContext ctx;

  public AdminTenantsController(
      DbStrategyConfigReader strategyConfigReader,
      AdminTenantAccountReader accountReader,
      LivePromotionStateReader livePromotionStateReader,
      TenantContext ctx) {
    this.strategyConfigReader = strategyConfigReader;
    this.accountReader = accountReader;
    this.livePromotionStateReader = livePromotionStateReader;
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> list(HttpServletRequest req) {
    // 400 if X-Operator-Id absent/malformed; 403 (before any tenant data is read/echoed) if the
    // operator is not in the OPERATOR_ALLOWLIST.
    String operator = ctx.requireAllowlistedOperator(req);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    List<Map<String, Object>> items = new ArrayList<>();
    for (TenantStrategyBrokerTarget s : strategyConfigReader.listAll()) {
      items.add(toItem(s, now));
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("operator_id", operator);
    body.put("count", items.size());
    body.put("items", items);
    return ResponseEntity.ok(body);
  }

  private Map<String, Object> toItem(TenantStrategyBrokerTarget s, OffsetDateTime now) {
    String brokerTarget = s.brokerTarget();
    boolean live = brokerTarget != null && brokerTarget.endsWith("-live");

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("tenant_id", s.tenantId());
    m.put("strategy_id", s.strategyId());
    m.put("broker_target", brokerTarget);
    m.put("account_masked", mask(accountReader.accountId(s.tenantId(), brokerTarget)));
    m.put("mode", live ? "live" : "paper");

    if (live) {
      LivePromotionState st =
          livePromotionStateReader.stateOf(s.tenantId(), s.strategyId(), brokerTarget, now);
      m.put("activation_state", st.state().name());
      // expires_at only when an approval exists AND the promotion is currently VALID (the "valid
      // until" the dashboard renders); STALE/DEACTIVATED carry no live "valid until".
      m.put(
          "expires_at",
          st.state() == LivePromotionStateReader.State.VALID ? st.expiresAt().toString() : null);
      m.put("at_risk", st.atRisk());
    } else {
      // Paper never hits the promotion gate.
      m.put("activation_state", "n/a");
      m.put("expires_at", null);
      m.put("at_risk", false);
    }

    // TODO(I-1 follow-up): kill_switch_state + last_synced. No trivially-reusable BFF reader
    // surfaces per-(tenant,strategy) kill-switch state without a new Temporal query/visibility
    // call, which would balloon I-1a's scope beyond the tenant list + masked account + activation
    // badge. Surfaced as null here so the response shape is forward-stable for I-1 follow-up.
    m.put("kill_switch_state", null);
    m.put("last_synced", null);
    return m;
  }

  /**
   * Mask a broker account to {@code ••••} + last 4 chars. A null/blank/short (&lt;4) account masks
   * to {@code ••••} with no suffix — never reveals a partial short account.
   */
  static String mask(String account) {
    String dots = "••••";
    if (account == null || account.length() < 4) {
      return dots;
    }
    return dots + account.substring(account.length() - 4);
  }
}
