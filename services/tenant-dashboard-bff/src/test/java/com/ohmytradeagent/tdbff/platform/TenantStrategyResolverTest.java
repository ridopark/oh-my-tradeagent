package com.ohmytradeagent.tdbff.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TenantStrategyResolverTest {

  @Test
  void resolvesOnlyTheRequestedTenantsStrategies(@TempDir Path tenants) throws Exception {
    writeStrategy(tenants, "acme", "alpha");
    writeStrategy(tenants, "acme", "beta");
    writeStrategy(tenants, "other", "gamma"); // must NOT leak into acme's set

    TenantStrategyResolver resolver = new TenantStrategyResolver(tenants.toString());

    assertThat(resolver.strategyIdsForTenant("acme")).containsExactlyInAnyOrder("alpha", "beta");
    assertThat(resolver.strategyIdsForTenant("other")).containsExactly("gamma");
    assertThat(resolver.strategyIdsForTenant("nobody")).isEmpty();
  }

  private static void writeStrategy(Path tenants, String tenant, String strategy) throws Exception {
    Path dir = tenants.resolve(tenant).resolve("strategies");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(strategy + ".yaml"), "strategy_id: " + strategy + "\n");
  }
}
