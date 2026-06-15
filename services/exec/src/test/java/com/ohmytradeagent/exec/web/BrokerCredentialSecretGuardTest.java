package com.ohmytradeagent.exec.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * P6-c MF-7 secret-hygiene guard (mirrors the spirit of audit-svc's {@code KindRegistryGuardTest}).
 * Mechanically enforces "the broker api-key/secret never reaches a Temporal/codegen type":
 *
 * <ul>
 *   <li>the secret field names ({@code apiKeyId}/{@code apiSecretKey} and the JSON spellings {@code
 *       api-key-id}/{@code api-secret-key}/{@code api_key_id}/{@code api_secret_key}) appear in NO
 *       file under {@code contract/schemas/} — so no JSON Schema is ever codegen'd into a DTO /
 *       pydantic model carrying the secret;
 *   <li>the exec-internal request record {@link BrokerCredentialWriteRequest} is NOT in the {@code
 *       com.ohmytradeagent.contract} package (it is a private HTTP body, never a contract type);
 *   <li>no exec {@code @ActivityInterface} references the request/response record — belt-and-braces
 *       that the secret-bearing type is unreachable from a Temporal activity input (Temporal
 *       persists activity inputs in history; a secret there is a hard MF-7 violation).
 * </ul>
 *
 * <p>The contract-schemas scan locates the monorepo root by walking up to the {@code services/} +
 * {@code contract/} + {@code pom.xml} parent (same idiom as {@code KindRegistryGuardTest}); if the
 * {@code contract/schemas} dir is absent in a partial checkout the scan is skipped via {@code
 * assumeTrue}. The package + activity-interface assertions run unconditionally (they read the exec
 * module's own sources, always present when this test runs).
 */
class BrokerCredentialSecretGuardTest {

  // Case-insensitive needles covering Java camelCase + JSON snake/kebab spellings.
  private static final List<String> FORBIDDEN_IN_SCHEMAS =
      List.of(
          "apikeyid",
          "apisecretkey",
          "api-key-id",
          "api-secret-key",
          "api_key_id",
          "api_secret_key");

  @Test
  void secretFieldNamesNeverAppearInContractSchemas() throws IOException {
    Path monorepoRoot = findMonorepoRoot();
    Path schemasDir = monorepoRoot.resolve("contract/schemas");

    Assumptions.assumeTrue(
        Files.isDirectory(schemasDir),
        "contract/schemas not present in this checkout — schema scan skipped");

    List<String> offenders = new ArrayList<>();
    Files.walkFileTree(
        schemasDir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            String name = file.getFileName().toString();
            if (!name.endsWith(".json")) {
              return FileVisitResult.CONTINUE;
            }
            String content = Files.readString(file).toLowerCase();
            for (String needle : FORBIDDEN_IN_SCHEMAS) {
              if (content.contains(needle)) {
                offenders.add(file + " contains forbidden secret field token: " + needle);
              }
            }
            return FileVisitResult.CONTINUE;
          }
        });

    assertThat(offenders)
        .as(
            "MF-7: a broker api-key/secret field name leaked into contract/schemas/ — that schema "
                + "would be codegen'd into a DTO/pydantic type and could be carried through Temporal "
                + "history. The secret must travel only via the exec-internal HTTP body. Offenders: %s",
            offenders)
        .isEmpty();
  }

  @Test
  void secretBearingRequestRecordIsNotAContractType() {
    String pkg = BrokerCredentialWriteRequest.class.getPackageName();
    assertThat(pkg)
        .as(
            "MF-7: the secret-bearing request record must NOT live in the contract (codegen) package")
        .doesNotStartWith("com.ohmytradeagent.contract");
    assertThat(pkg).isEqualTo("com.ohmytradeagent.exec.web");
  }

  @Test
  void noExecActivityInterfaceReferencesTheSecretRecord() throws IOException {
    Path monorepoRoot = findMonorepoRoot();
    Path execSrc = monorepoRoot.resolve("services/exec/src/main/java/com/ohmytradeagent/exec");

    Assumptions.assumeTrue(
        Files.isDirectory(execSrc), "exec source not present — activity-interface scan skipped");

    String requestSimpleName = BrokerCredentialWriteRequest.class.getSimpleName();
    String responseSimpleName = BrokerCredentialWriteResponse.class.getSimpleName();
    // The record sources themselves name @ActivityInterface in their MF-7 javadoc (and their own
    // class name) — exclude them so we only flag a DIFFERENT file that actually wires the type into
    // a Temporal activity contract.
    String requestFileName = requestSimpleName + ".java";
    String responseFileName = responseSimpleName + ".java";

    List<String> offenders = new ArrayList<>();
    Files.walkFileTree(
        execSrc,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            String fileName = file.getFileName().toString();
            if (!fileName.endsWith(".java")
                || fileName.equals(requestFileName)
                || fileName.equals(responseFileName)) {
              return FileVisitResult.CONTINUE;
            }
            String content = Files.readString(file);
            if (content.contains("@ActivityInterface")
                && (content.contains(requestSimpleName) || content.contains(responseSimpleName))) {
              offenders.add(file.toString());
            }
            return FileVisitResult.CONTINUE;
          }
        });

    assertThat(offenders)
        .as(
            "MF-7: an exec @ActivityInterface references the secret-bearing HTTP record %s/%s — "
                + "Temporal persists activity inputs in history, so the secret would land in the "
                + "durable event log. The record must be reachable only from the HTTP controller. "
                + "Offenders: %s",
            requestSimpleName, responseSimpleName, offenders)
        .isEmpty();
  }

  private static Path findMonorepoRoot() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && p.getParent() != null) {
      if (Files.isDirectory(p.resolve("services"))
          && Files.isDirectory(p.resolve("contract"))
          && Files.isRegularFile(p.resolve("pom.xml"))) {
        return p;
      }
      p = p.getParent();
    }
    return Path.of("").toAbsolutePath();
  }
}
