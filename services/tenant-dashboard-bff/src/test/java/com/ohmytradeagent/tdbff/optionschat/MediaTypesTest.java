package com.ohmytradeagent.tdbff.optionschat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * MediaTypes is load-bearing for the mirror's security story: it decides the Content-Type the
 * browser is told for third-party bytes served from the dashboard's OWN origin. If a hostile upload
 * could be served as {@code text/html}, it would be stored XSS.
 */
class MediaTypesTest {

  /** A 64-byte buffer whose first bytes are the given magic. int... so char literals work. */
  private static byte[] withTail(int... magic) {
    byte[] out = new byte[64];
    for (int i = 0; i < magic.length; i++) {
      out[i] = (byte) magic[i];
    }
    return out;
  }

  @Test
  void recognisesTheImageFormatsDiscordActuallyServes() {
    assertThat(MediaTypes.sniff(withTail(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)))
        .isEqualTo("image/png");
    assertThat(MediaTypes.sniff(withTail(0xFF, 0xD8, 0xFF, 0xE0))).isEqualTo("image/jpeg");
    assertThat(MediaTypes.sniff(withTail('G', 'I', 'F', '8', '9', 'a'))).isEqualTo("image/gif");

    byte[] webp = new byte[64];
    System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, webp, 0, 4);
    System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, webp, 8, 4);
    assertThat(MediaTypes.sniff(webp)).isEqualTo("image/webp");
  }

  @Test
  void htmlIsNeverServedAsHtml_theWholePointOfSniffing() {
    // The attack: upload something that renders as markup, get it served from the dashboard's own
    // origin, and you have stored XSS against a session that can force-exit real-money positions.
    byte[] html = new byte[64];
    byte[] src = "<html><script>alert(1)</script>".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(src, 0, html, 0, src.length);

    assertThat(MediaTypes.sniff(html)).isEqualTo(MediaTypes.OCTET_STREAM);
  }

  @Test
  void svgIsNotTreatedAsAnImage_becauseItCanCarryScript() {
    byte[] svg = new byte[64];
    byte[] src =
        "<svg xmlns=\"http://www.w3.org/2000/svg\"><script/>".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(src, 0, svg, 0, src.length);

    assertThat(MediaTypes.sniff(svg)).isEqualTo(MediaTypes.OCTET_STREAM);
  }

  @Test
  void anythingUnrecognisedOrTooShortFallsBackToOctetStream() {
    assertThat(MediaTypes.sniff(null)).isEqualTo(MediaTypes.OCTET_STREAM);
    assertThat(MediaTypes.sniff(new byte[0])).isEqualTo(MediaTypes.OCTET_STREAM);
    // Shorter than the WebP probe needs — must not index out of bounds.
    assertThat(MediaTypes.sniff(new byte[] {'R', 'I', 'F', 'F'}))
        .isEqualTo(MediaTypes.OCTET_STREAM);
    assertThat(MediaTypes.sniff(withTail('n', 'o', 'p', 'e'))).isEqualTo(MediaTypes.OCTET_STREAM);
  }

  @Test
  void aPngPrefixOnNonPngBytesIsStillPng_sniffingIsNotValidation() {
    // Documents the boundary honestly: this identifies a format, it does not validate the file.
    // Safety comes from the allowlist of types plus nosniff, not from the bytes being well-formed.
    assertThat(MediaTypes.sniff(withTail(0x89, 'P', 'N', 'G', 0, 0, 0, 0))).isEqualTo("image/png");
  }
}
