package com.ohmytradeagent.exec.web;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * A1 (self-service-copytrade-onboarding) verified-account READ. Reads ONLY the non-secret {@code
 * expected_account_id} column from a {@code (tenant, provider)} row in the per-broker exec DB's
 * {@code broker_credentials} table — a column-only {@code SELECT}, NEVER {@link
 * com.ohmytradeagent.exec.broker.alpaca.DbBrokerCredentialSource#resolve} (which materializes the
 * decrypted secret). Ciphertext / DEK / IV columns are never touched.
 *
 * <p>A row "verifies" IFF its {@code expected_account_id} is non-blank — the same live-seal
 * predicate {@link com.ohmytradeagent.exec.broker.alpaca.DbBrokerCredentialSource} enforces before
 * serving a credential (a blank account id means the P2 account-identity assertion would no-op, so
 * such a row is treated as NOT verified). A missing row or a blank account id both return {@link
 * Optional#empty()}.
 *
 * <p><b>Dark by construction.</b> Gated identically to {@link BrokerCredentialAdminController} —
 * {@code broker.creds.source=db} AND an {@code alpaca-*} impl — so on a homelab pod (selector at
 * {@code env}) this bean does not exist.
 */
@Component
@ConditionalOnExpression("'${broker.impl:}'.startsWith('alpaca-')")
@ConditionalOnProperty(name = "broker.creds.source", havingValue = "db")
public class BrokerCredentialAccountReader {

  private static final String TABLE = "broker_credentials";
  // Column-only projection: the NON-secret expected_account_id, keyed by (tenant, provider). The
  // ciphertext/wrapped_dek/iv columns are intentionally never referenced here (C6).
  private static final Field<String> EXPECTED_ACCOUNT_ID =
      field("expected_account_id", String.class);
  private static final Field<String> TENANT_ID = field("tenant_id", String.class);
  private static final Field<String> PROVIDER = field("provider", String.class);

  private final DSLContext dsl;

  public BrokerCredentialAccountReader(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * The verified account id for {@code (tenant, provider)}, or empty when no row exists or the
   * row's {@code expected_account_id} is blank. Uses {@code isBlank()} (not {@code isEmpty()}) to
   * match the {@link com.ohmytradeagent.exec.broker.alpaca.DbBrokerCredentialSource} live-seal
   * predicate exactly — a whitespace-only account id must not read as verified.
   */
  public Optional<String> verifiedAccount(String tenant, String provider) {
    String account =
        dsl.select(EXPECTED_ACCOUNT_ID)
            .from(table(TABLE))
            .where(TENANT_ID.eq(tenant))
            .and(PROVIDER.eq(provider))
            .fetchOne(EXPECTED_ACCOUNT_ID);
    if (account == null || account.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(account);
  }
}
