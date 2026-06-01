package com.ohmytradeagent.tdbff.platform;

// COPIED FROM services/orchestrator/.../bootstrap/TenantStrategyScanner.java — keep in sync.
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Walks the {@code tenants/} directory and emits one {@link TenantStrategy} record per {@code
 * tenants/<tenant>/strategies/<strategy>.yaml}. Fails fast on I/O error.
 */
public final class TenantStrategyScanner {

  private static final String YAML_SUFFIX = ".yaml";

  private TenantStrategyScanner() {}

  public static List<TenantStrategy> scan(Path tenantsDir) {
    List<TenantStrategy> out = new ArrayList<>();
    for (Path tenantDir : listSubdirs(tenantsDir)) {
      String tenantId = tenantDir.getFileName().toString();
      Path strategiesDir = tenantDir.resolve("strategies");
      if (!Files.exists(strategiesDir)) {
        continue;
      }
      for (Path file : listYamlFiles(strategiesDir)) {
        String name = file.getFileName().toString();
        String strategyId = name.substring(0, name.length() - YAML_SUFFIX.length());
        out.add(new TenantStrategy(tenantId, strategyId));
      }
    }
    return out;
  }

  private static List<Path> listSubdirs(Path dir) {
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(Files::isDirectory).toList();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to list " + dir, e);
    }
  }

  private static List<Path> listYamlFiles(Path dir) {
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(p -> p.toString().endsWith(YAML_SUFFIX)).toList();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to list " + dir, e);
    }
  }

  public record TenantStrategy(String tenantId, String strategyId) {}
}
