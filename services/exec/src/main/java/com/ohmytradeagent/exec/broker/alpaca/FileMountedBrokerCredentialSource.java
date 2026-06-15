package com.ohmytradeagent.exec.broker.alpaca;

import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.BrokerCredentialSource;
import com.ohmytradeagent.exec.broker.BrokerCredentials;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * P4-b per-tenant credential source: reads each {@code (tenant, provider)} key's credentials from a
 * k8s-mounted scoped secret — a directory of per-field files under {@code
 * <root>/<tenant>-<provider>/}:
 *
 * <ul>
 *   <li>{@code api-key-id}, {@code api-secret-key}, {@code base-url} — REQUIRED (missing/blank →
 *       throw)
 *   <li>{@code ws-url}, {@code expected-account-id} — optional ({@code ""} when absent/blank)
 * </ul>
 *
 * <p><b>Dark by default.</b> Selected only by {@code broker.creds.source=file} (mutually exclusive
 * with {@link EnvFallbackBrokerCredentialSource}'s {@code env} default), so the homelab pods —
 * which leave the selector at {@code env} — resolve credentials byte-identically to P4-a. A k8s
 * Secret projected as a volume gives exactly this per-field-file layout with atomic {@code ..data}
 * symlink-swap on rotation (the P4-b-2 rotation hook).
 *
 * <p><b>Fail-closed.</b> A missing directory, a missing/blank required field, or — on a {@code
 * -live} pod — a blank {@code expected-account-id} all THROW out of {@link #resolve}, which
 * propagates out of the registry's {@code computeIfAbsent} so no client is cached and no order is
 * placed against an unverified account. The source NEVER falls back to the env cred set (that would
 * defeat tenant isolation and risk a cross-account order) and NEVER logs or includes secret
 * material in an exception message.
 *
 * <p><b>Live requires a declared account.</b> Under per-tenant creds a blank {@code
 * expected-account-id} would silently disable the P2 account-identity assertion ({@link
 * BrokerAccountIdentityVerifier} no-ops on blank), so on a {@code -live} pod this source treats it
 * as required — a live tenant must declare the account its keys authenticate.
 *
 * <p><b>Account-level reads.</b> The account-wide Activities (snapshot / pre-trade /
 * reconciliation) resolve with {@link BrokerClientRegistry#ACCOUNT_LEVEL}, which is not a real
 * tenant directory. In the one-account-per-pod model those reads belong to the pod's single
 * account, so the sentinel maps to the configured {@code broker.creds.account-level-tenant}
 * directory; an unresolved/blank value there is a fail-closed throw (never a default tenant).
 *
 * <p><b>Single-mode per pod.</b> {@code AlpacaModeCoherence.assertCoherent} keys off the pod-wide
 * {@code broker.impl}, so this source does NOT enable mixing paper and live tenants in one pod —
 * true multi-account-per-pod is P4-c.
 */
@Component
@ConditionalOnExpression("'${broker.impl:}'.startsWith('alpaca-')")
@ConditionalOnProperty(name = "broker.creds.source", havingValue = "file")
public class FileMountedBrokerCredentialSource implements BrokerCredentialSource {

  static final String FIELD_API_KEY_ID = "api-key-id";
  static final String FIELD_API_SECRET_KEY = "api-secret-key";
  static final String FIELD_BASE_URL = "base-url";
  static final String FIELD_WS_URL = "ws-url";
  static final String FIELD_EXPECTED_ACCOUNT_ID = "expected-account-id";

  private final Path root;
  private final String accountLevelTenant;
  private final boolean live;

  public FileMountedBrokerCredentialSource(
      @Value("${broker.creds.file.root:/etc/broker-creds}") String root,
      @Value("${broker.creds.account-level-tenant:${EXEC_BOOTSTRAP_TENANT_ID:}}")
          String accountLevelTenant,
      @Value("${broker.impl:}") String brokerImpl) {
    this.root = Path.of(root);
    this.accountLevelTenant = accountLevelTenant;
    this.live = brokerImpl != null && brokerImpl.endsWith("-live");
  }

  @Override
  public BrokerCredentials resolve(String tenantId, String provider) {
    String tenant = resolveTenant(tenantId);
    Path dir = root.resolve(scopedDirName(tenant, provider));
    if (!Files.isDirectory(dir)) {
      // Missing mount / typo'd tenant: a deployment error, not transient — retrying a missing k8s
      // secret accomplishes nothing. The path (a mount path, never contents) is the only detail.
      throw unavailable(
          "no credential directory for tenant=" + tenant + " provider=" + provider + " at " + dir);
    }
    String apiKeyId = readRequired(dir, FIELD_API_KEY_ID, tenant, provider);
    String apiSecretKey = readRequired(dir, FIELD_API_SECRET_KEY, tenant, provider);
    String baseUrl = readRequired(dir, FIELD_BASE_URL, tenant, provider);
    String wsUrl = readOptional(dir, FIELD_WS_URL);
    String expectedAccountId = readOptional(dir, FIELD_EXPECTED_ACCOUNT_ID);

    // MUST-FIX-1 (cross-account foot-gun seal): on a -live pod a blank expected-account-id would
    // silently disable the P2 account-identity assertion (verify() no-ops on blank). A live tenant
    // MUST declare the account its keys authenticate, so a blank here fails closed.
    if (live && expectedAccountId.isEmpty()) {
      throw unavailable(
          "missing/blank required cred field '"
              + FIELD_EXPECTED_ACCOUNT_ID
              + "' for tenant="
              + tenant
              + " provider="
              + provider
              + " at "
              + dir
              + " — a -live target must declare its expected account");
    }
    return new BrokerCredentials(apiKeyId, apiSecretKey, baseUrl, wsUrl, expectedAccountId);
  }

  /**
   * Maps the {@link BrokerClientRegistry#ACCOUNT_LEVEL} sentinel to the configured account-level
   * tenant; passes any real tenant through unchanged. An {@code ACCOUNT_LEVEL} request with no
   * configured {@code broker.creds.account-level-tenant} fails closed rather than guessing a
   * tenant.
   */
  private String resolveTenant(String tenantId) {
    if (!BrokerClientRegistry.ACCOUNT_LEVEL.equals(tenantId)) {
      return tenantId;
    }
    if (accountLevelTenant == null || accountLevelTenant.isBlank()) {
      throw unavailable(
          "account-level credential resolution requested but broker.creds.account-level-tenant is"
              + " unset — refusing to guess a tenant");
    }
    return accountLevelTenant;
  }

  private String readRequired(Path dir, String field, String tenant, String provider) {
    String value = readFile(dir.resolve(field));
    if (value == null || value.isEmpty()) {
      throw unavailable(
          "missing/blank required cred field '"
              + field
              + "' for tenant="
              + tenant
              + " provider="
              + provider
              + " at "
              + dir);
    }
    return value;
  }

  private String readOptional(Path dir, String field) {
    String value = readFile(dir.resolve(field));
    return value == null ? "" : value;
  }

  /**
   * Reads a single field file UTF-8 and strips surrounding whitespace (the trailing newline most
   * secret tooling appends). Returns {@code null} when the file is absent. A read {@link
   * IOException} — e.g. caught mid {@code ..data} symlink swap — is wrapped {@link
   * IllegalStateException} so the Activity's default retry covers the sub-millisecond window; the
   * contents are NEVER in the message.
   */
  private String readFile(Path path) {
    if (!Files.isRegularFile(path)) {
      return null;
    }
    try {
      return Files.readString(path, StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      throw new IllegalStateException("failed reading broker credential file at " + path, e);
    }
  }

  /**
   * Builds the {@code <tenant>-<provider>} scoped directory name, rejecting path-traversal input
   * ({@code /}, {@code ..}, NUL, or blank) fail-closed so a crafted tenant/provider cannot escape
   * the mount root. The name is only ever constructed, never parsed back, so a tenant containing a
   * {@code -} is unambiguous.
   */
  static String scopedDirName(String tenant, String provider) {
    rejectUnsafe("tenant", tenant);
    rejectUnsafe("provider", provider);
    return tenant + "-" + provider;
  }

  private static void rejectUnsafe(String label, String value) {
    if (value == null || value.isBlank()) {
      throw unavailable("blank " + label + " for credential resolution");
    }
    if (value.contains("/") || value.contains("..") || value.indexOf('\0') >= 0) {
      throw unavailable("unsafe " + label + " for credential resolution: '" + value + "'");
    }
  }

  private static ApplicationFailure unavailable(String message) {
    return ApplicationFailure.newNonRetryableFailure(message, "BrokerCredentialsUnavailable");
  }
}
