package com.ohmytradeagent.tdbff.optionschat;

/**
 * Decides an attachment's Content-Type from the BYTES, never from anything the caller says.
 *
 * <p>This is the second half of a rule the ingest already enforces: {@code IngestAttachment}
 * carries no {@code contentType} field at all, so Discord's claim never even reaches us. Sniffing
 * here closes the loop — the value the browser is told is derived from what we actually stored, so
 * a hostile upload cannot get itself served as {@code text/html} and become stored XSS on the
 * dashboard's own origin.
 *
 * <p>Anything unrecognised is served as {@code application/octet-stream}, which browsers download
 * rather than render. Combined with {@code X-Content-Type-Options: nosniff} on the response, an
 * unknown blob cannot be re-interpreted as markup.
 */
public final class MediaTypes {

  public static final String OCTET_STREAM = "application/octet-stream";

  private MediaTypes() {}

  /** The image type these bytes actually are, or {@code application/octet-stream}. */
  public static String sniff(byte[] b) {
    if (b == null || b.length < 12) {
      return OCTET_STREAM;
    }
    // PNG: 89 50 4E 47 0D 0A 1A 0A
    if (u(b[0]) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
      return "image/png";
    }
    // JPEG: FF D8 FF
    if (u(b[0]) == 0xFF && u(b[1]) == 0xD8 && u(b[2]) == 0xFF) {
      return "image/jpeg";
    }
    // GIF87a / GIF89a
    if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
      return "image/gif";
    }
    // WebP: "RIFF" .... "WEBP"
    if (b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P') {
      return "image/webp";
    }
    return OCTET_STREAM;
  }

  private static int u(byte x) {
    return x & 0xFF;
  }
}
