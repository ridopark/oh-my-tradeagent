package com.ohmytradeagent.exec.broker.alpaca;

import com.ohmytradeagent.exec.broker.BrokerCredentialSource;
import com.ohmytradeagent.exec.broker.BrokerCredentials;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The P4-a credential source: env-fallback. {@code resolve(tenantId, provider)} IGNORES tenantId
 * and returns the SAME {@link BrokerCredentials} — the single env cred set ({@link
 * AlpacaProperties} + the fill-listener WS URL + {@code EXPECTED_ALPACA_ACCOUNT_ID}) — for EVERY
 * tenant.
 *
 * <p>env-fallback returns the single env cred set for ALL tenants → byte-identical to the pre-P4a
 * single broker; P4-b adds the per-tenant impl.
 *
 * <p>Activated for any {@code alpaca-*} broker.impl (mirrors {@link AlpacaConfig}), so a {@code
 * BROKER_IMPL=stub} container never constructs this bean. {@code broker.creds.source} selects
 * between this {@code env} default ({@code matchIfMissing}) and the per-tenant {@link
 * FileMountedBrokerCredentialSource} ({@code file}); the two are mutually exclusive, so an
 * unrecognized selector value yields NO source bean (fail-closed crash, never a silent default).
 */
@Component
@ConditionalOnExpression("'${broker.impl:}'.startsWith('alpaca-')")
@ConditionalOnProperty(name = "broker.creds.source", havingValue = "env", matchIfMissing = true)
public class EnvFallbackBrokerCredentialSource implements BrokerCredentialSource {

  private final AlpacaProperties props;
  private final String wsUrl;
  private final String expectedAccountId;
  private final String bootstrapTenant;

  public EnvFallbackBrokerCredentialSource(
      AlpacaProperties props,
      @Value("${exec.fill-listener.ws-url:}") String wsUrl,
      @Value("${EXPECTED_ALPACA_ACCOUNT_ID:}") String expectedAccountId,
      @Value("${broker.creds.account-level-tenant:${EXEC_BOOTSTRAP_TENANT_ID:}}")
          String bootstrapTenant) {
    this.props = props;
    this.wsUrl = wsUrl;
    this.expectedAccountId = expectedAccountId;
    this.bootstrapTenant = bootstrapTenant;
  }

  @Override
  public BrokerCredentials resolve(String tenantId, String provider) {
    // tenantId is intentionally ignored: the env-fallback maps every tenant to the single env cred
    // set so the registry-resolved broker is byte-identical to the pre-P4-a single broker. The
    // values come field-for-field from AlpacaProperties (apiKeyId / apiSecretKey / baseUrl) plus
    // the
    // WS URL + expected account id. P4-b replaces this with the per-tenant source.
    return new BrokerCredentials(
        props.apiKeyId(), props.apiSecretKey(), props.baseUrl(), wsUrl, expectedAccountId);
  }

  /**
   * The env-fallback pod serves a single account, so its roster is exactly the single bootstrap
   * tenant ({@code broker.creds.account-level-tenant} / {@code EXEC_BOOTSTRAP_TENANT_ID} — the same
   * id the {@code ACCOUNT_LEVEL} sentinel and the account-identity probe map to). A blank bootstrap
   * tenant yields an EMPTY roster so the per-tenant listener opens no sockets rather than
   * authenticating under a blank tenant id.
   */
  @Override
  public Set<String> liveTenants(String provider) {
    if (bootstrapTenant == null || bootstrapTenant.isBlank()) {
      return Set.of();
    }
    return Set.of(bootstrapTenant);
  }
}
