package com.ohmytradeagent.tdbff.platform;

// COPIED FROM services/orchestrator/.../platform/YamlStrategyRegistry.java — keep in sync.
// Read-only here: the BFF only needs each strategy's broker_target to pick the exec datasource /
// broker task queue.
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ohmytradeagent.contract.StrategyConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class YamlStrategyRegistry {

  // Thread-safe for concurrent reads; one instance suffices for the singleton bean.
  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private final Path tenantsDir;

  public YamlStrategyRegistry(@Value("${bff.tenants-dir:tenants}") String tenantsDir) {
    this.tenantsDir = Path.of(tenantsDir);
  }

  public StrategyConfig get(String tenantId, String strategyId) {
    Path file = tenantsDir.resolve(tenantId).resolve("strategies").resolve(strategyId + ".yaml");
    if (!Files.exists(file)) {
      throw new StrategyNotFoundException("Strategy YAML not found at " + file.toAbsolutePath());
    }
    try {
      return YAML.readValue(file.toFile(), StrategyConfig.class);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to parse " + file.toAbsolutePath(), e);
    }
  }

  /** The {@code broker_target} (e.g. {@code alpaca-paper}) for a (tenant, strategy). */
  public String brokerTarget(String tenantId, String strategyId) {
    StrategyConfig cfg = get(tenantId, strategyId);
    return cfg.getBrokerTarget() == null ? null : cfg.getBrokerTarget().value();
  }

  public static class StrategyNotFoundException extends RuntimeException {
    public StrategyNotFoundException(String message) {
      super(message);
    }
  }
}
