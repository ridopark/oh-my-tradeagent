package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
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
 * MF-7 secret-hygiene guard for the api-gateway forward record (mirrors exec's {@code
 * BrokerCredentialSecretGuardTest}). Mechanically enforces:
 *
 * <ul>
 *   <li>{@link BrokerCredentialForwardRequest} is NOT in the {@code com.ohmytradeagent.contract}
 *       (codegen) package — so the secret-bearing type can never be codegen'd or carried through
 *       Temporal history;
 *   <li>no api-gateway {@code @WorkflowInterface}/{@code @ActivityInterface} references the
 *       secret-bearing record;
 *   <li>(belt-and-braces) no field of the forward record is itself a {@code com.ohmytradeagent
 *       .contract} type — the secret never reaches a contract DTO by composition.
 * </ul>
 */
class BrokerCredentialForwardSecretGuardTest {

  @Test
  void secretBearingForwardRecordIsNotAContractType() {
    String pkg = BrokerCredentialForwardRequest.class.getPackageName();
    assertThat(pkg)
        .as(
            "MF-7: the secret-bearing forward record must NOT live in the contract (codegen) package")
        .doesNotStartWith("com.ohmytradeagent.contract");
    assertThat(pkg).isEqualTo("com.ohmytradeagent.apigateway.web");
  }

  @Test
  void noForwardRecordFieldIsAContractType() {
    for (RecordComponent c : BrokerCredentialForwardRequest.class.getRecordComponents()) {
      assertThat(c.getType().getName())
          .as("MF-7: forward record field '%s' must not be a contract type", c.getName())
          .doesNotStartWith("com.ohmytradeagent.contract");
    }
  }

  @Test
  void noApiGatewayTemporalInterfaceReferencesTheSecretRecord() throws IOException {
    Path monorepoRoot = findMonorepoRoot();
    Path apiGwSrc =
        monorepoRoot.resolve("services/api-gateway/src/main/java/com/ohmytradeagent/apigateway");

    Assumptions.assumeTrue(
        Files.isDirectory(apiGwSrc), "api-gateway source not present — interface scan skipped");

    String simpleName = BrokerCredentialForwardRequest.class.getSimpleName();
    String ownFile = simpleName + ".java";

    List<String> offenders = new ArrayList<>();
    Files.walkFileTree(
        apiGwSrc,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            String fileName = file.getFileName().toString();
            if (!fileName.endsWith(".java") || fileName.equals(ownFile)) {
              return FileVisitResult.CONTINUE;
            }
            String content = Files.readString(file);
            if ((content.contains("@WorkflowInterface") || content.contains("@ActivityInterface"))
                && content.contains(simpleName)) {
              offenders.add(file.toString());
            }
            return FileVisitResult.CONTINUE;
          }
        });

    assertThat(offenders)
        .as(
            "MF-7: an api-gateway Temporal interface references the secret-bearing forward record"
                + " %s — Temporal persists inputs in history. Offenders: %s",
            simpleName, offenders)
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
