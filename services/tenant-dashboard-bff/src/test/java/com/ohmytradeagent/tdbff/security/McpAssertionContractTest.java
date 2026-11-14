package com.ohmytradeagent.tdbff.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.tdbff.security.DelegatedAssertionAuthenticator.Status;
import org.junit.jupiter.api.Test;

/**
 * Cross-language assertion encoding contract.
 *
 * <p>The Python MCP gateway signs compact JWS using PyJWT + Ed25519 with the private key whose DER
 * base64 matches {@link DelegatedAssertionTestKeys#PRIMARY_PUBLIC_KEY_B64}. This test drives the
 * production {@link DelegatedAssertionAuthenticator} with assertions assembled by the Java
 * hand-roller ({@link DelegatedAssertionTestKeys}), which uses the identical encoding, proving that
 * Python and Java agree on:
 *
 * <ul>
 *   <li>Base64url no-padding for header, payload, and signature
 *   <li>UTF-8 JSON for header and payload
 *   <li>ASCII {@code "header.payload"} as the signed input
 *   <li>Required claim set: iss, aud, provider, sub, tenant, client_id, iat, exp as JSON integers
 *   <li>Lifetime: exp − iat == 60 (Python default); ≤ 300 s max enforced by the verifier
 * </ul>
 *
 * <p>This is a pure unit test: no Spring context, no DB, no network.
 */
class McpAssertionContractTest {

  private static final String SUB = "sub-1";
  private static final String TENANT = "acme";
  private static final String PROVIDER = "google";

  /** SHA-256("google:sub-1")[:12 hex] — computed independently with sha256sum. */
  private static final String EXPECTED_CREDENTIAL_ID = "dlg-daf0e67a0269";

  private static DelegatedAssertionAuthenticator newAuth(boolean memberResult) {
    DashboardMembershipReader reader = mock(DashboardMembershipReader.class);
    when(reader.isMember(PROVIDER, SUB, TENANT)).thenReturn(memberResult);
    // Instantiate directly — @Value/@Component annotations are ignored outside Spring.
    return new DelegatedAssertionAuthenticator(
        DelegatedAssertionTestKeys.PRIMARY_PUBLIC_KEY_B64, reader);
  }

  // ---------------------------------------------------------------------------
  // Happy-path: primary key, member present
  // ---------------------------------------------------------------------------

  @Test
  void primaryKeyAssertion_memberPresent_isOk() {
    DelegatedAssertionAuthenticator auth = newAuth(true);
    String jws =
        DelegatedAssertionTestKeys.build(
            DelegatedAssertionTestKeys.validHeader(),
            DelegatedAssertionTestKeys.validClaims(SUB, TENANT));

    DelegatedAssertionAuthenticator.Result result = auth.authenticate(jws);

    assertThat(result.status()).isEqualTo(Status.OK);
    assertThat(result.tenant()).isEqualTo(TENANT);
    assertThat(result.credentialId()).isEqualTo(EXPECTED_CREDENTIAL_ID);
    assertThat(result.clientId()).isEqualTo("test-client");
  }

  @Test
  void primaryKeyAssertion_memberAbsent_isNotMember() {
    DelegatedAssertionAuthenticator auth = newAuth(false);
    String jws =
        DelegatedAssertionTestKeys.build(
            DelegatedAssertionTestKeys.validHeader(),
            DelegatedAssertionTestKeys.validClaims(SUB, TENANT));

    DelegatedAssertionAuthenticator.Result result = auth.authenticate(jws);

    assertThat(result.status()).isEqualTo(Status.NOT_MEMBER);
    assertThat(result.tenant()).isEqualTo(TENANT);
    assertThat(result.credentialId()).isEqualTo(EXPECTED_CREDENTIAL_ID);
  }

  // ---------------------------------------------------------------------------
  // Key mismatch: secondary key → INVALID (not the configured public key)
  // ---------------------------------------------------------------------------

  @Test
  void secondaryKeyAssertion_isInvalid() {
    DelegatedAssertionAuthenticator auth = newAuth(true);
    String jws =
        DelegatedAssertionTestKeys.buildWithSecondaryKey(
            DelegatedAssertionTestKeys.validHeader(),
            DelegatedAssertionTestKeys.validClaims(SUB, TENANT));

    assertThat(auth.authenticate(jws).status()).isEqualTo(Status.INVALID);
  }

  // ---------------------------------------------------------------------------
  // Tampered payload: signature does not cover the replacement payload
  // ---------------------------------------------------------------------------

  @Test
  void tamperedPayload_isInvalid() {
    DelegatedAssertionAuthenticator auth = newAuth(true);
    var legitimate = DelegatedAssertionTestKeys.validClaims(SUB, TENANT);
    var tampered = new java.util.HashMap<>(legitimate);
    tampered.put("tenant", "evil");
    String jws =
        DelegatedAssertionTestKeys.buildTamperedPayload(
            DelegatedAssertionTestKeys.validHeader(), legitimate, tampered);

    assertThat(auth.authenticate(jws).status()).isEqualTo(Status.INVALID);
  }
}
