package com.ohmytradeagent.orchestrator.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phase 6: YAML-backed {@link TenantRegistry} reading {@code tenants/<tenant>/tenant.yaml}. Mirrors
 * {@link YamlStrategyRegistry}. The tenant.yaml file already exists for layout ({@code
 * tenant_id}/{@code display_name}/{@code strategies}); this is the first reader of it.
 *
 * <p>A missing tenant.yaml yields a default {@link TenantConfig} (null threshold => account cap
 * disabled) so a tenant that has not opted into the cap is fully inert — no exception, no trip.
 */
public class YamlTenantRegistry implements TenantRegistry {

  private final Path tenantsDir;
  private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

  public YamlTenantRegistry(Path tenantsDir) {
    this.tenantsDir = tenantsDir;
  }

  @Override
  public TenantConfig get(String tenantId) {
    Path file = tenantsDir.resolve(tenantId).resolve("tenant.yaml");
    if (!Files.exists(file)) {
      return new TenantConfig();
    }
    try {
      TenantConfig cfg = yaml.readValue(file.toFile(), TenantConfig.class);
      return cfg == null ? new TenantConfig() : cfg;
    } catch (IOException e) {
      throw new IllegalStateException("Failed to parse " + file.toAbsolutePath(), e);
    }
  }
}
