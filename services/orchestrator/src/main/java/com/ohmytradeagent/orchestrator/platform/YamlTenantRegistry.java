package com.ohmytradeagent.orchestrator.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Phase 6: YAML-backed {@link TenantRegistry} reading {@code tenants/<tenant>/tenant.yaml}. Mirrors
 * {@link YamlStrategyRegistry}. The tenant.yaml file already exists for layout ({@code
 * tenant_id}/{@code display_name}/{@code strategies}); this is the first reader of it.
 *
 * <p>A missing tenant.yaml yields a default {@link TenantConfig} (null threshold => account cap
 * disabled) so a tenant that has not opted into the cap is fully inert — no exception, no trip.
 *
 * <p>account-loss-cap-db epic (Phase 1): the active {@link TenantRegistry} bean is property-driven
 * exactly like the strategy path — {@code @ConditionalOnProperty(name = "tenant.config.source",
 * havingValue = "yaml", matchIfMissing = true)}. Default (property unset) = Yaml, so switching to
 * {@link DbTenantRegistry} is a deliberate operator opt-in ({@code tenant.config.source=db}).
 */
@Component
@ConditionalOnProperty(name = "tenant.config.source", havingValue = "yaml", matchIfMissing = true)
public class YamlTenantRegistry implements TenantRegistry {

  private final Path tenantsDir;
  private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

  public YamlTenantRegistry(@Value("${orchestrator.tenants-dir:tenants}") String tenantsDir) {
    this.tenantsDir = Path.of(tenantsDir);
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
