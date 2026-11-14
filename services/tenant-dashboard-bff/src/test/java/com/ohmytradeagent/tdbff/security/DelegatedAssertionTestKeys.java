package com.ohmytradeagent.tdbff.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Test helper: pre-generated Ed25519 key pairs and compact JWS assembly, independent of the
 * production {@link DelegatedAssertionAuthenticator} class.
 *
 * <p>Both key pairs were generated offline with {@code openssl genpkey -algorithm ed25519}; the
 * constants below are the PKCS8 DER (private) and SPKI DER (public), base64-encoded. Using
 * hardcoded constants (not runtime generation) means {@code @TestPropertySource} can reference the
 * primary public key as a compile-time literal.
 *
 * <p>The JWS assembly here is intentionally hand-rolled — it must NOT share code with the
 * production verifier, so a bug in the verifier cannot self-cancel in a test.
 */
public final class DelegatedAssertionTestKeys {

  /**
   * Primary key pair. The public key is what {@code bff.mcp-delegated.public-key} is set to in
   * {@link McpDelegatedEndToEndWebMvcTest} and in the delegated-assertion unit tests.
   *
   * <p>SPKI DER (base64) — goes into {@code bff.mcp-delegated.public-key}: {@code
   * MCowBQYDK2VwAyEAHisUEeiCZJqA+lLdttZH5el0TQdI3kdV7z9sDK1X0Ck=}
   */
  public static final String PRIMARY_PUBLIC_KEY_B64 =
      "MCowBQYDK2VwAyEAHisUEeiCZJqA+lLdttZH5el0TQdI3kdV7z9sDK1X0Ck=";

  /**
   * Secondary key pair — used to sign assertions that the BFF must reject (wrong key). The
   * secondary public key is NEVER configured as {@code bff.mcp-delegated.public-key}.
   */
  public static final String SECONDARY_PUBLIC_KEY_B64 =
      "MCowBQYDK2VwAyEAmOnc6ID71ifjzfmEp/h9ZJggrjIkqcOHadWGqqJhFcI=";

  private static final String PRIMARY_PRIVATE_KEY_B64 =
      "MC4CAQAwBQYDK2VwBCIEIGdpg7tnNVS+T9MsfJWD1DkvHlJjdTD8p+pxsDvWMNZ7";

  private static final String SECONDARY_PRIVATE_KEY_B64 =
      "MC4CAQAwBQYDK2VwBCIEINJ2DxbG7i5rOS/LmTnu/jRBrDBgh28ZF6dROKqDGR6K";

  private static final PrivateKey PRIMARY_PRIVATE_KEY = loadPrivateKey(PRIMARY_PRIVATE_KEY_B64);
  private static final PrivateKey SECONDARY_PRIVATE_KEY = loadPrivateKey(SECONDARY_PRIVATE_KEY_B64);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private DelegatedAssertionTestKeys() {}

  /**
   * Returns a {@code validHeader()} map: {@code {"alg":"EdDSA","typ":"JWT"}}. Using a plain map
   * lets individual tests override a single key for negative cases (e.g. {@code alg = "none"}).
   */
  public static Map<String, Object> validHeader() {
    return Map.of("alg", "EdDSA", "typ", "JWT");
  }

  /**
   * Returns a valid claims map with {@code iss}, {@code aud}, {@code provider}, the given {@code
   * sub} and {@code tenant}, a {@code client_id}, and {@code iat}/{@code exp} set to now/now+60.
   */
  public static Map<String, Object> validClaims(String sub, String tenant) {
    long now = Instant.now().getEpochSecond();
    return Map.of(
        "iss",
        "omt-mcp-gateway",
        "aud",
        "tenant-dashboard-bff",
        "provider",
        "google",
        "sub",
        sub,
        "tenant",
        tenant,
        "client_id",
        "test-client",
        "iat",
        now,
        "exp",
        now + 60);
  }

  /**
   * Builds a compact JWS signed with the PRIMARY private key. The header and payload are each
   * JSON-serialized and base64url-encoded (no padding). The signature is Ed25519 over the ASCII
   * bytes of {@code "header.payload"}.
   */
  public static String build(Map<String, Object> header, Map<String, Object> payload) {
    return buildWith(header, payload, PRIMARY_PRIVATE_KEY);
  }

  /**
   * Builds a compact JWS signed with the SECONDARY private key. Used to exercise the wrong-key
   * rejection path.
   */
  public static String buildWithSecondaryKey(
      Map<String, Object> header, Map<String, Object> payload) {
    return buildWith(header, payload, SECONDARY_PRIVATE_KEY);
  }

  /**
   * Builds a compact JWS with a tampered payload: signs with the primary key over the legitimate
   * header+payload, then replaces the payload with a different one. The resulting token is
   * structurally valid but its signature does not match.
   */
  public static String buildTamperedPayload(
      Map<String, Object> header, Map<String, Object> legitimate, Map<String, Object> replacement) {
    String headerB64 = toB64Url(toJson(header));
    String legitimateB64 = toB64Url(toJson(legitimate));
    String replacementB64 = toB64Url(toJson(replacement));
    byte[] sigInput = (headerB64 + "." + legitimateB64).getBytes(StandardCharsets.US_ASCII);
    String sigB64 = sign(sigInput, PRIMARY_PRIVATE_KEY);
    // The signature was computed over legitimate; this token uses replacement.
    return headerB64 + "." + replacementB64 + "." + sigB64;
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private static String buildWith(
      Map<String, Object> header, Map<String, Object> payload, PrivateKey key) {
    String headerB64 = toB64Url(toJson(header));
    String payloadB64 = toB64Url(toJson(payload));
    byte[] sigInput = (headerB64 + "." + payloadB64).getBytes(StandardCharsets.US_ASCII);
    String sigB64 = sign(sigInput, key);
    return headerB64 + "." + payloadB64 + "." + sigB64;
  }

  private static String sign(byte[] input, PrivateKey key) {
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(key);
      signer.update(input);
      byte[] sig = signer.sign();
      return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
    } catch (Exception e) {
      throw new IllegalStateException("Ed25519 sign failed in test helper", e);
    }
  }

  private static String toB64Url(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  private static String toJson(Map<String, Object> map) {
    try {
      return MAPPER.writeValueAsString(map);
    } catch (Exception e) {
      throw new IllegalStateException("JSON serialization failed in test helper", e);
    }
  }

  private static PrivateKey loadPrivateKey(String b64) {
    try {
      byte[] der = Base64.getDecoder().decode(b64);
      return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load Ed25519 test private key", e);
    }
  }
}
