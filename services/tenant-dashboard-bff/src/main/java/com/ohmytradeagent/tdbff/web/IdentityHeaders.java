package com.ohmytradeagent.tdbff.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/**
 * Extracts and validates the {@code X-Identity-Provider} and {@code X-Identity-Subject} headers
 * injected by the dashboard server for identity-scoped routes (e.g. the "Connected AI apps" listing
 * and revoke). These headers are trusted on the same basis as {@code X-Tenant-Id}: they arrive from
 * the dashboard server behind the shared service token, never directly from the browser.
 *
 * <p>This is a pure helper (not a Spring bean). The caller catches {@link MissingIdentityException}
 * → 401 or {@link InvalidIdentityException} → 400 and returns the appropriate {@code
 * ResponseEntity}; {@code GlobalExceptionHandler} is not modified.
 */
public final class IdentityHeaders {

  static final String HEADER_PROVIDER = "X-Identity-Provider";
  static final String HEADER_SUBJECT = "X-Identity-Subject";

  // provider: lowercase ASCII letters only, 1–32 chars (matches the OAuth provider id).
  private static final Pattern PROVIDER_PATTERN = Pattern.compile("[a-z]{1,32}");
  // subject: the OAuth `sub` claim — alphanumerics and ._- only, 1–255 chars.
  private static final Pattern SUBJECT_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,255}");

  private final String provider;
  private final String subject;

  private IdentityHeaders(String provider, String subject) {
    this.provider = provider;
    this.subject = subject;
  }

  /**
   * Extracts and validates both identity headers from the request.
   *
   * @throws MissingIdentityException if either header is absent or blank
   * @throws InvalidIdentityException if a header value does not match its allowed charset
   */
  public static IdentityHeaders extract(HttpServletRequest request) {
    String provider = request.getHeader(HEADER_PROVIDER);
    String subject = request.getHeader(HEADER_SUBJECT);

    if (provider == null || provider.isBlank() || subject == null || subject.isBlank()) {
      throw new MissingIdentityException();
    }
    if (!PROVIDER_PATTERN.matcher(provider).matches()) {
      throw new InvalidIdentityException("invalid " + HEADER_PROVIDER);
    }
    if (!SUBJECT_PATTERN.matcher(subject).matches()) {
      throw new InvalidIdentityException("invalid " + HEADER_SUBJECT);
    }
    return new IdentityHeaders(provider, subject);
  }

  public String provider() {
    return provider;
  }

  public String subject() {
    return subject;
  }

  /** Thrown when {@code X-Identity-Provider} or {@code X-Identity-Subject} is absent or blank. */
  public static class MissingIdentityException extends RuntimeException {
    MissingIdentityException() {
      super("missing required headers: " + HEADER_PROVIDER + ", " + HEADER_SUBJECT);
    }
  }

  /** Thrown when an identity header value does not match its allowed charset. */
  public static class InvalidIdentityException extends RuntimeException {
    InvalidIdentityException(String detail) {
      super(detail);
    }
  }
}
