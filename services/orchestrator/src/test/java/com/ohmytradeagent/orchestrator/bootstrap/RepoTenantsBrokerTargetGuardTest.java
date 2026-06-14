package com.ohmytradeagent.orchestrator.bootstrap;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ohmytradeagent.orchestrator.platform.YamlStrategyRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Issue #323 part (b) CI guard: validates the repository's real {@code tenants/} tree against the
 * cross-tenant {@code broker_target} invariant on every {@code mvn verify} (the CI "Java" job). A
 * future config change that maps two distinct tenants to the same {@code broker_target} fails this
 * test — and therefore CI — before it can ship.
 */
class RepoTenantsBrokerTargetGuardTest {

  @Test
  void repoTenantsTreeHasNoCrossTenantBrokerTargetConflict() {
    Path tenantsDir = locateRepoTenantsDir();
    // If the tree cannot be located from the test working dir, skip rather than false-fail. The
    // unit tests in CrossTenantBrokerTargetValidatorTest cover the validator logic directly.
    assumeTrue(tenantsDir != null, "repo tenants/ dir not found from test working directory");

    assertThatCode(
            () ->
                CrossTenantBrokerTargetValidator.validate(
                    tenantsDir, new YamlStrategyRegistry(tenantsDir.toString())))
        .doesNotThrowAnyException();
  }

  /** Walks up from the working directory to find the repo-root {@code tenants/} directory. */
  private static Path locateRepoTenantsDir() {
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 6 && dir != null; i++) {
      Path candidate = dir.resolve("tenants");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    return null;
  }
}
