package com.ohmytradeagent.tdbff.security;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Checks whether a {@code (provider, subject, tenant_id)} triple is a current row in {@code
 * dashboard_user}. Used by {@link DelegatedAssertionAuthenticator} to re-verify membership on every
 * delegated assertion request.
 *
 * <p>DARK by default: the bean is absent when {@code bff.mcp-delegated.enabled} is {@code false}
 * (the repo default), so no connection is opened and no password is needed until the flag is set.
 *
 * <p>Fail-closed: any SQL exception is caught, logged at WARN (class name only — never the
 * subject), and treated as {@code false}. A DB hiccup during token verification denies access
 * rather than granting it.
 */
@Component
@ConditionalOnProperty(name = "bff.mcp-delegated.enabled", havingValue = "true")
public class DashboardMembershipReader {

  private static final Logger log = LoggerFactory.getLogger(DashboardMembershipReader.class);

  private final DSLContext dsl;

  public DashboardMembershipReader(@Qualifier("dashboardReadonlyDsl") DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * Returns {@code true} iff {@code (provider, subject, tenantId)} has a row in {@code
   * dashboard_user}. Any exception returns {@code false} (fail-closed).
   */
  public boolean isMember(String provider, String subject, String tenantId) {
    try {
      return !dsl.fetch(
              "SELECT 1 FROM dashboard_user"
                  + " WHERE provider = ? AND subject = ? AND tenant_id = ? LIMIT 1",
              provider,
              subject,
              tenantId)
          .isEmpty();
    } catch (Exception e) {
      log.warn("dashboard_user membership check failed: {}", e.getClass().getSimpleName());
      return false;
    }
  }
}
