package com.ohmytradeagent.orchestrator.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.ohmytradeagent.contract.StrategyConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "strategy.config.source", havingValue = "yaml", matchIfMissing = true)
public class YamlStrategyRegistry implements StrategyRegistry {

  private final Path tenantsDir;
  private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

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

  public static class StrategyNotFoundException extends RuntimeException {
    public StrategyNotFoundException(String message) {
      super(message);
    }
  }
}
