package com.ohmytradeagent.tdbff.credentials;

// Read-only, NON-SECRET status view of services/exec/.../db/exec/V5__broker_credentials.sql
// (broker_credentials, in the exec_alpaca_paper DB that execAlpacaPaperDsl targets). The SELECT is
// a strict allowlist: it NEVER reads ciphertext/iv/wrapped_dek/dek_iv/kek_version so an encrypted
// blob can never leak through this endpoint. The dashboard uses it to render "configured / version
// N / account" and to compute expected_version for the credential-write CAS.
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Non-secret broker-credential status rows for a tenant, ordered by provider. */
@Component
public class BrokerCredentialStatusReader {

  private final DSLContext dsl;

  // Single-broker MVP: bind directly to the alpaca-paper exec DB. OrdersReader routes through
  // BrokerDataSourceRouter to union a tenant's multiple broker DBs; when a second broker DB lands,
  // this reader must do the same or it will silently only see alpaca-paper credentials.
  public BrokerCredentialStatusReader(@Qualifier("execAlpacaPaperDsl") DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * One status row per configured (tenant, provider). A returned row implies the credential is
   * configured. Only non-secret columns are selected — the encrypted blob columns are never read.
   */
  public List<Map<String, Object>> statuses(String tenantId) {
    return dsl
        .select(
            DSL.field("provider"),
            // version is BIGINT — type it so jOOQ returns Long (the dashboard's CAS
            // expected_version).
            DSL.field("version", Long.class),
            DSL.field("expected_account_id"),
            DSL.field("updated_at", OffsetDateTime.class),
            DSL.field("updated_by"))
        .from(DSL.table("broker_credentials"))
        .where(DSL.field("tenant_id").eq(tenantId))
        .orderBy(DSL.field("provider"))
        .fetch()
        .stream()
        .map(BrokerCredentialStatusReader::row)
        .toList();
  }

  private static Map<String, Object> row(Record r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("provider", r.get("provider"));
    m.put("configured", true); // a returned row ⇒ configured
    m.put("version", r.get("version", Long.class));
    m.put("broker_account_id", r.get("expected_account_id"));
    m.put("updated_at", r.get("updated_at"));
    m.put("updated_by", r.get("updated_by"));
    return m;
  }
}
