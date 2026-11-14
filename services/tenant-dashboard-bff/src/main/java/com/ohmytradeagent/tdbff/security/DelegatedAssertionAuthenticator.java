package com.ohmytradeagent.tdbff.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Verifies EdDSA delegated assertions issued by the MCP gateway and maps them to the same read
 * rules as a static MCP read token.
 *
 * <p>An assertion is a compact JWS: {@code header.payload.sig} (base64url, no padding). The header
 * must have {@code alg == "EdDSA"}; the payload carries {@code iss, aud, provider, sub, tenant,
 * client_id, iat, exp}. The signature is verified with {@code java.security.Signature("Ed25519")}
 * over the ASCII bytes of {@code header.payload} — no new Maven dependency; JDK 21 provides it.
 *
 * <p>DARK by default: the bean is absent when {@code bff.mcp-delegated.enabled} is {@code false}
 * (the repo default). Enabling the flag with an empty or unparseable key throws {@link
 * IllegalStateException} at boot — same stance as the malformed MCP token map.
 */
@Component
@ConditionalOnProperty(name = "bff.mcp-delegated.enabled", havingValue = "true")
public class DelegatedAssertionAuthenticator {

  /** Outcome of one {@link #authenticate} call. */
  public enum Status {
    /** The string does not look like a compact JWS (no two dots, or non-JSON parts). */
    NOT_AN_ASSERTION,
    /** Looks like an assertion but fails signature, alg, or claims validation. */
    INVALID,
    /** Valid assertion for a subject that is not a member of the claimed tenant. */
    NOT_MEMBER,
    /** Valid assertion; the subject is a current member of the claimed tenant. */
    OK
  }

  public record Result(Status status, String tenant, String credentialId, String clientId) {}

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String TENANT_PATTERN = "[A-Za-z0-9_-]+";
  // Safe charset for client_id: covers DCR uuid4 values and CIMD https:// URLs.
  // Excludes newlines and other control characters that could be injected into log lines.
  private static final String CLIENT_ID_PATTERN = "[A-Za-z0-9_.:/@-]{1,512}";

  private final PublicKey publicKey;
  private final DashboardMembershipReader membershipReader;

  public DelegatedAssertionAuthenticator(
      @Value("${bff.mcp-delegated.public-key:}") String publicKeyBase64,
      DashboardMembershipReader membershipReader) {
    if (publicKeyBase64 == null || publicKeyBase64.isBlank()) {
      throw new IllegalStateException(
          "bff.mcp-delegated.enabled=true but bff.mcp-delegated.public-key is empty or missing");
    }
    try {
      byte[] der = Base64.getDecoder().decode(publicKeyBase64.strip());
      X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
      this.publicKey = KeyFactory.getInstance("Ed25519").generatePublic(spec);
    } catch (Exception e) {
      throw new IllegalStateException(
          "bff.mcp-delegated.public-key is not a valid Ed25519 SPKI DER (base64): "
              + e.getMessage(),
          e);
    }
    this.membershipReader = membershipReader;
  }

  /**
   * Authenticates a bearer token string as a compact JWS delegated assertion.
   *
   * @return {@link Status#NOT_AN_ASSERTION} when the string does not have exactly two dots or
   *     either part is not a JSON object — signals the filter to try the next credential class.
   *     {@link Status#INVALID} for any structural or cryptographic failure — the filter returns
   *     401. {@link Status#NOT_MEMBER} when the assertion is valid but the subject is not a member
   *     of the claimed tenant — 403. {@link Status#OK} on success.
   */
  public Result authenticate(String presented) {
    // Three-part compact JWS: header.payload.sig — exactly two dots.
    int first = presented.indexOf('.');
    if (first < 0) return new Result(Status.NOT_AN_ASSERTION, null, null, null);
    int second = presented.indexOf('.', first + 1);
    if (second < 0) return new Result(Status.NOT_AN_ASSERTION, null, null, null);
    if (presented.indexOf('.', second + 1) >= 0)
      return new Result(Status.NOT_AN_ASSERTION, null, null, null);

    String headerB64 = presented.substring(0, first);
    String payloadB64 = presented.substring(first + 1, second);
    String sigB64 = presented.substring(second + 1);

    // Decode and parse header + payload as JSON objects.
    Map<String, Object> header, payload;
    try {
      header = decodeJsonObject(headerB64);
      payload = decodeJsonObject(payloadB64);
    } catch (Exception e) {
      return new Result(Status.NOT_AN_ASSERTION, null, null, null);
    }

    // alg must be exactly "EdDSA" — anything else (including "none", "HS256", "RS256") is INVALID.
    if (!"EdDSA".equals(header.get("alg"))) {
      return new Result(Status.INVALID, null, null, null);
    }

    // Verify Ed25519 signature over the ASCII bytes of "header.payload".
    try {
      byte[] sigBytes = Base64.getUrlDecoder().decode(addPadding(sigB64));
      byte[] sigInput = (headerB64 + "." + payloadB64).getBytes(StandardCharsets.US_ASCII);
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(publicKey);
      verifier.update(sigInput);
      if (!verifier.verify(sigBytes)) {
        return new Result(Status.INVALID, null, null, null);
      }
    } catch (Exception e) {
      return new Result(Status.INVALID, null, null, null);
    }

    // Validate required claims.
    try {
      if (!"omt-mcp-gateway".equals(requireString(payload, "iss")))
        return new Result(Status.INVALID, null, null, null);
      if (!"tenant-dashboard-bff".equals(requireString(payload, "aud")))
        return new Result(Status.INVALID, null, null, null);

      String provider = requireString(payload, "provider");
      if (!"google".equals(provider)) return new Result(Status.INVALID, null, null, null);

      String sub = requireString(payload, "sub");
      if (sub.isBlank() || sub.length() > 255) return new Result(Status.INVALID, null, null, null);

      String tenant = requireString(payload, "tenant");
      if (!tenant.matches(TENANT_PATTERN)) return new Result(Status.INVALID, null, null, null);

      String clientId = requireString(payload, "client_id");
      if (!clientId.matches(CLIENT_ID_PATTERN)) return new Result(Status.INVALID, null, null, null);

      long now = Instant.now().getEpochSecond();
      long iat = requireLong(payload, "iat");
      long exp = requireLong(payload, "exp");
      if (exp <= now) return new Result(Status.INVALID, null, null, null);
      if (iat > now + 30) return new Result(Status.INVALID, null, null, null);
      // Guard against iat > exp and long overflow (e.g. iat=-9e18, exp=9e18 → subtractExact
      // throws).
      long lifetime;
      try {
        lifetime = Math.subtractExact(exp, iat);
      } catch (ArithmeticException e) {
        return new Result(Status.INVALID, null, null, null);
      }
      if (lifetime < 0 || lifetime > 300) return new Result(Status.INVALID, null, null, null);

      String credentialId = "dlg-" + sha256prefix(provider + ":" + sub, 12);
      if (!membershipReader.isMember(provider, sub, tenant)) {
        return new Result(Status.NOT_MEMBER, tenant, credentialId, clientId);
      }
      return new Result(Status.OK, tenant, credentialId, clientId);
    } catch (Exception e) {
      return new Result(Status.INVALID, null, null, null);
    }
  }

  // ---------------------------------------------------------------------------

  private static Map<String, Object> decodeJsonObject(String b64url) throws Exception {
    byte[] json = Base64.getUrlDecoder().decode(addPadding(b64url));
    Object parsed = MAPPER.readValue(json, Object.class);
    if (!(parsed instanceof Map<?, ?> m)) {
      throw new IllegalArgumentException(
          "not a JSON object: " + (parsed == null ? "null" : parsed.getClass().getSimpleName()));
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) m;
    return result;
  }

  private static String requireString(Map<String, Object> map, String key) {
    Object v = map.get(key);
    if (!(v instanceof String s))
      throw new IllegalArgumentException("missing or non-string claim: " + key);
    return s;
  }

  private static long requireLong(Map<String, Object> map, String key) {
    Object v = map.get(key);
    if (v instanceof Number n) return n.longValue();
    throw new IllegalArgumentException("missing or non-numeric claim: " + key);
  }

  private static String addPadding(String b64url) {
    int pad = b64url.length() % 4;
    return pad == 0 ? b64url : b64url + "=".repeat(4 - pad);
  }

  private static String sha256prefix(String input, int hexChars) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash).substring(0, hexChars);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
