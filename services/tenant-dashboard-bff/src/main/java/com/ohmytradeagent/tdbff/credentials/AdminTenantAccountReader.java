package com.ohmytradeagent.tdbff.credentials;

import com.ohmytradeagent.tdbff.config.BrokerDataSourceRouter;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Operator-scoped, NON-SECRET account read for the admin tenant list. Given a (tenant,
 * broker_target) it returns ONLY {@code broker_credentials.expected_account_id} from the exec DB
 * that matches the broker_target's paper/live mode — never a ciphertext/secret column. Mirrors the
 * strict-allowlist SELECT of {@link BrokerCredentialStatusReader}; unlike that reader (hardwired to
 * the paper DB), this one resolves the exec DB via {@link BrokerDataSourceRouter} so the list can
 * show live accounts too.
 *
 * <p>The {@code broker_credentials} PK is {@code (tenant_id, provider)} where {@code provider} is
 * the broker_target's prefix (e.g. {@code alpaca-live} → {@code alpaca}); the paper/live
 * distinction is carried by WHICH exec DB the row lives in, not by the {@code provider} value.
 */
@Component
public class AdminTenantAccountReader {

  private final BrokerDataSourceRouter router;

  public AdminTenantAccountReader(BrokerDataSourceRouter router) {
    this.router = router;
  }

  /**
   * The raw {@code expected_account_id} for (tenant, broker_target), or {@code null} when no
   * credential row exists or the column is null. Fail-soft: never throws on a missing row or an
   * unconfigured broker_target. The CALLER masks; this returns the raw account so the mask lives in
   * one place (the controller's mapper). It is NOT a secret — it is the broker account number,
   * surfaced masked.
   */
  public String accountId(String tenantId, String brokerTarget) {
    if (!router.isConfigured(brokerTarget)) {
      return null;
    }
    DSLContext dsl = router.dslFor(brokerTarget);
    String provider = providerOf(brokerTarget);
    Record1<String> row =
        dsl.select(DSL.field("expected_account_id", String.class))
            .from(DSL.table("broker_credentials"))
            .where(DSL.field("tenant_id").eq(tenantId).and(DSL.field("provider").eq(provider)))
            .fetchOne();
    return row == null ? null : row.value1();
  }

  // The broker_credentials.provider is the substring before the first '-' (e.g. alpaca-live →
  // alpaca). Mirrors exec's BrokerClientRegistry.providerOf; inlined here to avoid a cross-module
  // dependency for one trivial string op.
  private static String providerOf(String brokerTarget) {
    int dash = brokerTarget.indexOf('-');
    return dash < 0 ? brokerTarget : brokerTarget.substring(0, dash);
  }
}
