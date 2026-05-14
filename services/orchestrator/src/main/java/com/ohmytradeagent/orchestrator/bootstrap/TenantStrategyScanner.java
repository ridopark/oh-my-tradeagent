package com.ohmytradeagent.orchestrator.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Walks the {@code tenants/} directory and emits one {@link TenantStrategy} record per {@code
 * tenants/<tenant>/strategies/<strategy>.yaml}. Both Phase 5 bootstrappers (kill switch +
 * reconciliation schedule) walk the same shape.
 *
 * <p>Fails fast on I/O error — a corrupted tenants tree should surface at boot, not be silently
 * ignored.
 */
final class TenantStrategyScanner {

  private static final String YAML_SUFFIX = ".yaml";

  private TenantStrategyScanner() {}

  static List<TenantStrategy> scan(Path tenantsDir) {
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

  record TenantStrategy(String tenantId, String strategyId) {}
}
