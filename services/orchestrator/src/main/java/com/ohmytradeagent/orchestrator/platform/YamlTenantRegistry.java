package com.ohmytradeagent.orchestrator.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
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

  /**
   * Tenant subdirectories under {@code tenantsDir}.
   *
   * <p>Dot-prefixed entries are EXCLUDED. When this directory is a Kubernetes ConfigMap mount it
   * also contains the atomic-write plumbing — {@code ..data} (a symlink, which {@code isDirectory}
   * follows) and a timestamped {@code ..2026_08_17_07_34_10.3332253454} directory. Counting those
   * as tenants is why the boot gate logged "validated for 5 tenant(s)" against a mount holding 3 on
   * 2026-08-17, and why it then called {@code get("..data")}. It only stayed harmless because a
   * missing tenant.yaml yields an inert default; a registry that failed closed on an unknown tenant
   * would have broken boot on a Kubernetes filename.
   */
  @Override
  public List<String> list() {
    if (!Files.exists(tenantsDir)) {
      return List.of();
    }
    try (Stream<Path> s = Files.list(tenantsDir)) {
      return s.filter(Files::isDirectory)
          .map(p -> p.getFileName().toString())
          .filter(name -> !name.startsWith("."))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to list tenants dir " + tenantsDir, e);
    }
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
