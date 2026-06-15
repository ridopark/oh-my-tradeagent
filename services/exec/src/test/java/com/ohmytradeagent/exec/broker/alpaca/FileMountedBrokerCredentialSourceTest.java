package com.ohmytradeagent.exec.broker.alpaca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.BrokerCredentials;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit-pins the P4-b file credential source's reading + fail-closed contract using a {@link
 * TempDir} mount tree (no Spring, no Testcontainers). Covers the happy path, per-tenant isolation,
 * the fail-closed throws (missing dir / blank required field / live-without-account / unsafe
 * input), the {@code ACCOUNT_LEVEL} mapping, optional-field defaulting, newline stripping, and
 * secret hygiene in thrown messages.
 */
class FileMountedBrokerCredentialSourceTest {

  private static final String PAPER = "alpaca-paper";
  private static final String LIVE = "alpaca-live";
  private static final String PAPER_HOST = "https://paper-api.alpaca.markets";

  @TempDir Path root;

  private FileMountedBrokerCredentialSource source(String accountLevelTenant, String brokerImpl) {
    return new FileMountedBrokerCredentialSource(root.toString(), accountLevelTenant, brokerImpl);
  }

  private void writeFull(String tenant, String keyId, String secret, String account)
      throws IOException {
    Path dir = Files.createDirectories(root.resolve(tenant + "-alpaca"));
    Files.writeString(dir.resolve("api-key-id"), keyId);
    Files.writeString(dir.resolve("api-secret-key"), secret);
    Files.writeString(dir.resolve("base-url"), PAPER_HOST);
    Files.writeString(dir.resolve("ws-url"), "wss://paper-api.alpaca.markets/stream");
    Files.writeString(dir.resolve("expected-account-id"), account);
  }

  @Test
  void readsPerTenantCredentialsDistinctly() throws IOException {
    writeFull("alice", "alice-key", "alice-secret", "111");
    writeFull("bob", "bob-key", "bob-secret", "222");
    var src = source("", PAPER);

    BrokerCredentials alice = src.resolve("alice", "alpaca");
    BrokerCredentials bob = src.resolve("bob", "alpaca");

    assertThat(alice.apiKeyId()).isEqualTo("alice-key");
    assertThat(alice.apiSecretKey()).isEqualTo("alice-secret");
    assertThat(alice.baseUrl()).isEqualTo(PAPER_HOST);
    assertThat(alice.wsUrl()).isEqualTo("wss://paper-api.alpaca.markets/stream");
    assertThat(alice.expectedAccountId()).isEqualTo("111");
    assertThat(bob.apiKeyId()).isEqualTo("bob-key");
    assertThat(bob.expectedAccountId()).isEqualTo("222");
  }

  @Test
  void stripsTrailingNewlineFromFields() throws IOException {
    writeFull("alice", "alice-key\n", "alice-secret\n", "111\n");
    BrokerCredentials c = source("", PAPER).resolve("alice", "alpaca");
    assertThat(c.apiKeyId()).isEqualTo("alice-key");
    assertThat(c.apiSecretKey()).isEqualTo("alice-secret");
    assertThat(c.expectedAccountId()).isEqualTo("111");
  }

  @Test
  void missingDirectoryFailsClosedNonRetryable() {
    assertThatThrownBy(() -> source("", PAPER).resolve("nope", "alpaca"))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            af -> {
              assertThat(af.getType()).isEqualTo("BrokerCredentialsUnavailable");
              assertThat(af.isNonRetryable()).isTrue();
            });
  }

  @Test
  void blankRequiredFieldFailsClosed() throws IOException {
    Path dir = Files.createDirectories(root.resolve("alice-alpaca"));
    Files.writeString(dir.resolve("api-key-id"), "alice-key");
    Files.writeString(dir.resolve("api-secret-key"), "alice-secret");
    Files.writeString(dir.resolve("base-url"), "   "); // blank-after-strip

    assertThatThrownBy(() -> source("", PAPER).resolve("alice", "alpaca"))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageContaining("base-url");
  }

  @Test
  void optionalFieldsDefaultToBlankWhenAbsent() throws IOException {
    Path dir = Files.createDirectories(root.resolve("alice-alpaca"));
    Files.writeString(dir.resolve("api-key-id"), "alice-key");
    Files.writeString(dir.resolve("api-secret-key"), "alice-secret");
    Files.writeString(dir.resolve("base-url"), PAPER_HOST);
    // no ws-url, no expected-account-id

    BrokerCredentials c = source("", PAPER).resolve("alice", "alpaca");
    assertThat(c.wsUrl()).isEmpty();
    assertThat(c.expectedAccountId()).isEmpty();
  }

  @Test
  void liveTargetWithBlankExpectedAccountFailsClosed() throws IOException {
    // MUST-FIX-1: a -live pod must not build a client with the P2 assertion silently disabled.
    Path dir = Files.createDirectories(root.resolve("alice-alpaca"));
    Files.writeString(dir.resolve("api-key-id"), "alice-key");
    Files.writeString(dir.resolve("api-secret-key"), "alice-secret");
    Files.writeString(dir.resolve("base-url"), "https://api.alpaca.markets");
    // expected-account-id intentionally absent

    assertThatThrownBy(() -> source("", LIVE).resolve("alice", "alpaca"))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            af -> assertThat(af.getType()).isEqualTo("BrokerCredentialsUnavailable"))
        .hasMessageContaining("expected-account-id");
  }

  @Test
  void paperTargetAllowsBlankExpectedAccount() throws IOException {
    Path dir = Files.createDirectories(root.resolve("alice-alpaca"));
    Files.writeString(dir.resolve("api-key-id"), "alice-key");
    Files.writeString(dir.resolve("api-secret-key"), "alice-secret");
    Files.writeString(dir.resolve("base-url"), PAPER_HOST);

    BrokerCredentials c = source("", PAPER).resolve("alice", "alpaca");
    assertThat(c.expectedAccountId()).isEmpty();
  }

  @Test
  void accountLevelSentinelMapsToConfiguredTenant() throws IOException {
    writeFull("pod-account", "pod-key", "pod-secret", "999");
    BrokerCredentials c =
        source("pod-account", PAPER).resolve(BrokerClientRegistry.ACCOUNT_LEVEL, "alpaca");
    assertThat(c.apiKeyId()).isEqualTo("pod-key");
    assertThat(c.expectedAccountId()).isEqualTo("999");
  }

  @Test
  void accountLevelWithUnconfiguredTenantFailsClosed() {
    assertThatThrownBy(
            () -> source("", PAPER).resolve(BrokerClientRegistry.ACCOUNT_LEVEL, "alpaca"))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageContaining("account-level-tenant");
  }

  @Test
  void pathTraversalTenantRejected() {
    assertThatThrownBy(() -> source("", PAPER).resolve("../escape", "alpaca"))
        .isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(() -> source("", PAPER).resolve("a/b", "alpaca"))
        .isInstanceOf(ApplicationFailure.class);
  }

  @Test
  void thrownMessageNeverLeaksSecretMaterial() throws IOException {
    // api-key-id + api-secret-key present and secret; base-url blank → required-field throw. The
    // message must name the field + path only, never the secret bytes.
    Path dir = Files.createDirectories(root.resolve("alice-alpaca"));
    Files.writeString(dir.resolve("api-key-id"), "SUPER-SECRET-KEY-ID");
    Files.writeString(dir.resolve("api-secret-key"), "SUPER-SECRET-VALUE");
    Files.writeString(dir.resolve("base-url"), "");

    assertThatThrownBy(() -> source("", PAPER).resolve("alice", "alpaca"))
        .isInstanceOf(ApplicationFailure.class)
        .hasMessageNotContaining("SUPER-SECRET-KEY-ID")
        .hasMessageNotContaining("SUPER-SECRET-VALUE");
  }

  @Test
  void fingerprintChangesWhenMountMtimeChanges() throws IOException {
    writeFull("alice", "alice-key", "alice-secret", "111");
    var src = source("", PAPER);
    String fp1 = src.fingerprint("alice", "alpaca");

    // Model a k8s Secret rotation: the projected mount's dir mtime bumps on the atomic ..data swap.
    Files.setLastModifiedTime(
        root.resolve("alice-alpaca"), java.nio.file.attribute.FileTime.fromMillis(0));
    String fp2 = src.fingerprint("alice", "alpaca");

    assertThat(fp2).isNotEqualTo(fp1);
  }

  @Test
  void fingerprintIsAbsentSentinelForMissingDirectory() {
    assertThat(source("", PAPER).fingerprint("nope", "alpaca")).isEqualTo("absent");
  }

  @Test
  void readsThroughDataSnapshotWhenPresent() throws IOException {
    // k8s projects a Secret as <dir>/..data/<field> (with <dir>/<field> symlinks into it). When a
    // ..data generation dir is present, resolve must read all fields from that single pinned
    // generation — here the ONLY copies live under ..data, so a successful read proves the routing.
    Path data = Files.createDirectories(root.resolve("alice-alpaca").resolve("..data"));
    Files.writeString(data.resolve("api-key-id"), "alice-key");
    Files.writeString(data.resolve("api-secret-key"), "alice-secret");
    Files.writeString(data.resolve("base-url"), PAPER_HOST);
    Files.writeString(data.resolve("expected-account-id"), "111");

    BrokerCredentials c = source("", PAPER).resolve("alice", "alpaca");
    assertThat(c.apiKeyId()).isEqualTo("alice-key");
    assertThat(c.expectedAccountId()).isEqualTo("111");
  }

  @Test
  void envFallbackSourceFingerprintIsConstant() {
    // The live-safety proof: the env source inherits the interface default (a constant), so the
    // registry never rebuilds it — byte-identical order path. Asserts no override leaked in.
    var env =
        new EnvFallbackBrokerCredentialSource(new AlpacaProperties(PAPER_HOST, "k", "s"), "", "");
    assertThat(env.fingerprint("alice", "alpaca"))
        .isEqualTo(env.fingerprint("bob", "alpaca"))
        .isEqualTo("static");
  }

  @Test
  void toStringRedactsCredentialFields() {
    BrokerCredentials c =
        new BrokerCredentials("the-key-id", "the-secret", PAPER_HOST, "wss://x", "123");
    assertThat(c.toString())
        .doesNotContain("the-key-id")
        .doesNotContain("the-secret")
        .contains(PAPER_HOST)
        .contains("123");
  }
}
