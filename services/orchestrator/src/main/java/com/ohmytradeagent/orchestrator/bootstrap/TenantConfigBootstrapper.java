package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.orchestrator.platform.TenantRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Live-safety boot gate for tenant-level config. On Spring start it LOADS every tenant's {@code
 * tenant.yaml} through the {@link TenantRegistry} so an out-of-range {@code account_daily_loss_pct}
 * fails the boot LOUDLY and EARLY — before any kill-switch workflow starts trading — instead of
 * silently disabling the account cap on real money or surfacing only as a confusing per-heartbeat
 * audit error later.
 *
 * <p>The actual bound check lives in {@link
 * com.ohmytradeagent.orchestrator.platform.TenantConfig#setAccountDailyLossPct} (rejects anything
 * outside {@code (0,1]} during Jackson parse). This bootstrapper just guarantees that parse runs at
 * startup for every tenant; the throw propagates and boot fails closed — mirroring {@link
 * LiveRequiredGateBootstrapper} for strategy config.
 *
 * <p>Enumeration walks the {@code tenants/<tenant>/} subdirectories DIRECTLY rather than via {@code
 * TenantStrategyScanner} — the scanner only emits tenants that have a {@code strategies/} subdir,
 * so a tenant whose {@code tenant.yaml} carries the cap but has no strategy files would otherwise
 * be skipped and its bad value never parsed. The config gate must cover the file it validates for
 * every tenant.
 *
 * <p>Ordered {@link Ordered#HIGHEST_PRECEDENCE}{@code + 11} so it runs alongside the other
 * live-safety gate and before the default-order {@link KillSwitchBootstrapper} starts any workflow.
 */
@Component
@Profile("!test")
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public class TenantConfigBootstrapper implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(TenantConfigBootstrapper.class);

  private final Path tenantsDir;
  private final TenantRegistry tenantRegistry;

  public TenantConfigBootstrapper(
      @Value("${orchestrator.tenants-dir:tenants}") String tenantsDir,
      TenantRegistry tenantRegistry) {
    this.tenantsDir = Path.of(tenantsDir);
    this.tenantRegistry = tenantRegistry;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!Files.exists(tenantsDir)) {
      log.warn("tenants dir {} not found; skipping tenant-config validation", tenantsDir);
      return;
    }
    List<Path> tenantDirs;
    try (Stream<Path> s = Files.list(tenantsDir)) {
      tenantDirs = s.filter(Files::isDirectory).toList();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to list tenants dir " + tenantsDir, e);
    }
    for (Path tenantDir : tenantDirs) {
      // Load through the registry: a bad account_daily_loss_pct throws here (setter rejects it),
      // and the throw propagates so boot fails closed rather than trading with a neutered cap.
      tenantRegistry.get(tenantDir.getFileName().toString());
    }
    log.info(
        "tenant-config invariant validated for {} tenant(s) in {}", tenantDirs.size(), tenantsDir);
  }
}
