package com.ohmytradeagent.orchestrator.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.bootstrap.TenantStrategyScanner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "strategy.config.source", havingValue = "yaml", matchIfMissing = true)
public class YamlStrategyRegistry implements StrategyRegistry {

  private final Path tenantsDir;
  // #649: reads are LENIENT (unknown keys ignored) so a dev YAML or the live tenants ConfigMap
  // carrying a since-removed schema key can never crash boot ENUM+SEEDING — the same
  // reads-forgive / writes-validate posture as the #772 Temporal converter. The /config write path
  // is
  // DTO-mediated (unknown keys dropped at the gateway binding) — reads and writes both forgive.
  private final ObjectMapper yaml =
      new ObjectMapper(new YAMLFactory())
          .configure(
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
              false);

  public YamlStrategyRegistry(@Value("${orchestrator.tenants-dir:tenants}") String tenantsDir) {
    this.tenantsDir = Path.of(tenantsDir);
  }

  @Override
  public StrategyConfig get(String tenantId, String strategyId) {
    Path file = tenantsDir.resolve(tenantId).resolve("strategies").resolve(strategyId + ".yaml");
    if (!Files.exists(file)) {
      throw new StrategyNotFoundException("Strategy YAML not found at " + file.toAbsolutePath());
    }
    try {
      return yaml.readValue(file.toFile(), StrategyConfig.class);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to parse " + file.toAbsolutePath(), e);
    }
  }

  /**
   * Enumerates {@code (tenant, strategy)} pairs by scanning the mounted {@code tenants/} ConfigMap
   * tree — preserving the dev enumeration source as the YAML-source behavior. If the tenants dir is
   * absent (e.g. a non-mounted environment) returns an empty list rather than throwing, so a
   * reconcile-loop tick degrades to a no-op instead of failing.
   */
  @Override
  public List<TenantStrategy> list() {
    if (!Files.exists(tenantsDir)) {
      return List.of();
    }
    return TenantStrategyScanner.scan(tenantsDir);
  }

  public static class StrategyNotFoundException extends RuntimeException {
    public StrategyNotFoundException(String message) {
      super(message);
    }
  }
}
