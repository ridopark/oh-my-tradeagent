package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.alert.TenantWebhookResolver;
import com.ohmytradeagent.orchestrator.alert.WebhookClient;
import com.ohmytradeagent.orchestrator.alert.WebhookEmbed;
import com.ohmytradeagent.orchestrator.platform.CapitalAllocator;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.YamlStrategyRegistry.StrategyNotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * C1 (P0c-b2) observability for the live strategy-config read. {@link #get} resolves config through
 * the active {@link StrategyRegistry} and stays FAIL-CLOSED: on any read failure it rethrows the
 * original exception unchanged (never returns a default/null) while emitting two best-effort
 * signals — a {@link #READ_FAILURES_COUNTER} increment (tagged tenant/strategy/reason) and a
 * deduplicated red Discord alert (one per failure episode, one resolve on recovery).
 *
 * <p>This is the Activity IMPL (recorded-result boundary), not workflow code — the metric/alert
 * side-effects do not affect Temporal replay. The counter idiom mirrors {@link
 * AccountSnapshotMetricsActivitiesImpl} ({@code _total} suffix, per-key cached {@link Counter});
 * the {@link WebhookClient} dispatch mirrors {@link WatchlistMirrorActivitiesImpl}. The
 * metric/alert are strictly best-effort: a meter or webhook failure is caught-and-logged and NEVER
 * alters the rethrown exception.
 */
@Component
public class StrategyActivitiesImpl implements StrategyActivities {

  static final String READ_FAILURES_COUNTER = "strategy_config_read_failures_total";

  /** 0xED4245 — Discord red (failure). Mirrors {@code AlertColors.RED} (package-private there). */
  private static final int DISCORD_RED = 15548997;

  private static final Logger log = LoggerFactory.getLogger(StrategyActivitiesImpl.class);

  private final StrategyRegistry registry;
  private final CapitalAllocator capitalAllocator;
  private final MeterRegistry meterRegistry;
  private final WebhookClient webhookClient;
  private final TenantWebhookResolver webhookResolver;

  private final ConcurrentMap<String, Counter> readFailureCounters = new ConcurrentHashMap<>();

  /** Keys {@code t|s|reason} for which a red alert has fired and not yet been resolved. */
  private final Set<String> alertedKeys = ConcurrentHashMap.newKeySet();

  public StrategyActivitiesImpl(
      StrategyRegistry registry,
      CapitalAllocator capitalAllocator,
      MeterRegistry meterRegistry,
      WebhookClient webhookClient,
      TenantWebhookResolver webhookResolver) {
    this.registry = registry;
    this.capitalAllocator = capitalAllocator;
    this.meterRegistry = meterRegistry;
    this.webhookClient = webhookClient;
    this.webhookResolver = webhookResolver;
  }

  @Override
  public StrategyConfig get(String tenantId, String strategyId) {
    try {
      StrategyConfig cfg = registry.get(tenantId, strategyId);
      onReadSuccess(tenantId, strategyId);
      return cfg;
    } catch (RuntimeException e) {
      String reason = classify(e);
      counter(tenantId, strategyId, reason).increment();
      maybeAlert(tenantId, strategyId, reason, e);
      throw e; // FAIL CLOSED — never swallow, never return a default.
    }
  }

  @Override
  public BigDecimal capitalForStrategy(String tenantId, String strategyId) {
    return capitalAllocator.capitalForStrategy(tenantId, strategyId);
  }

  private static String classify(RuntimeException e) {
    if (e instanceof StrategyNotFoundException) {
      return "not_found";
    }
    if (e instanceof IllegalStateException) {
      // DbStrategyRegistry throws IllegalStateException for two distinct conditions: a
      // newer-than-build schema_version (no cause) and an unparseable config blob (a
      // JsonProcessingException cause). Keep them as separate reasons — a corrupt blob and a
      // newer-schema row are different operational responses, and neither is a DB-I/O failure.
      if (e.getCause() instanceof com.fasterxml.jackson.core.JsonProcessingException) {
        return "config_parse";
      }
      if (e.getMessage() != null && e.getMessage().contains("schema_version")) {
        return "schema_version";
      }
    }
    return "db_error";
  }

  private Counter counter(String tenantId, String strategyId, String reason) {
    return readFailureCounters.computeIfAbsent(
        tenantId + "|" + strategyId + "|" + reason,
        k ->
            Counter.builder(READ_FAILURES_COUNTER)
                .description(
                    "Live strategy-config read failures (fail-closed: the read still rethrows). "
                        + "reason ∈ {not_found, schema_version, config_parse, db_error}.")
                .tag("tenant", tenantId)
                .tag("strategy", strategyId)
                .tag("reason", reason)
                .register(meterRegistry));
  }

  /**
   * Fires a single red alert per failure episode: posts only when {@code t|s|reason} is not already
   * alerted, then records it. Best-effort — any webhook/build error is caught-and-logged and never
   * propagated, so it cannot alter the rethrow in {@link #get}.
   */
  private void maybeAlert(String tenantId, String strategyId, String reason, RuntimeException e) {
    try {
      String key = tenantId + "|" + strategyId + "|" + reason;
      if (alertedKeys.add(key)) {
        String url = webhookResolver.resolve(tenantId, strategyId);
        webhookClient.postEmbedToUrl(url, failureEmbed(tenantId, strategyId, reason, e));
      }
    } catch (RuntimeException alertError) {
      log.warn(
          "strategy-config read-failure alert failed tenant={} strategy={} reason={}",
          tenantId,
          strategyId,
          reason,
          alertError);
    }
  }

  /**
   * On a successful read, resolves any outstanding alert for {@code (tenant, strategy)}: posts a
   * single green "resolved" alert and re-arms the key so the next failure episode alerts again.
   * Best-effort — never throws (a resolve failure must not break the success return path).
   */
  private void onReadSuccess(String tenantId, String strategyId) {
    // Fast path for the signal-frequency common case: nothing is alerted, so there is nothing to
    // re-arm — skip the prefix-string / list / iterator allocation entirely.
    if (alertedKeys.isEmpty()) {
      return;
    }
    try {
      String prefix = tenantId + "|" + strategyId + "|";
      List<String> resolved = new ArrayList<>();
      for (String key : alertedKeys) {
        if (key.startsWith(prefix) && alertedKeys.remove(key)) {
          resolved.add(key.substring(prefix.length()));
        }
      }
      for (String reason : resolved) {
        String url = webhookResolver.resolve(tenantId, strategyId);
        webhookClient.postEmbedToUrl(url, resolveEmbed(tenantId, strategyId, reason));
      }
    } catch (RuntimeException resolveError) {
      log.warn(
          "strategy-config read-recovery alert failed tenant={} strategy={}",
          tenantId,
          strategyId,
          resolveError);
    }
  }

  private static WebhookEmbed failureEmbed(
      String tenantId, String strategyId, String reason, RuntimeException e) {
    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("tenant_id", tenantId, false));
    fields.add(new WebhookEmbed.Field("strategy_id", strategyId, false));
    fields.add(new WebhookEmbed.Field("reason", reason, false));
    fields.add(
        new WebhookEmbed.Field(
            "error", e.getMessage() == null ? e.toString() : e.getMessage(), false));
    return new WebhookEmbed(
        ":rotating_light: Strategy-config read FAILED (fail-closed) — " + reason,
        null,
        DISCORD_RED,
        tenantId + "/" + strategyId,
        fields);
  }

  private static WebhookEmbed resolveEmbed(String tenantId, String strategyId, String reason) {
    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("tenant_id", tenantId, false));
    fields.add(new WebhookEmbed.Field("strategy_id", strategyId, false));
    fields.add(new WebhookEmbed.Field("prior_reason", reason, false));
    // 0x57F287 — Discord green (recovery).
    return new WebhookEmbed(
        ":white_check_mark: Strategy-config read RECOVERED — " + reason,
        null,
        5763719,
        tenantId + "/" + strategyId,
        fields);
  }
}
