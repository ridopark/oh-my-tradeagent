package com.ohmytradeagent.orchestrator.alert;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the Discord alert webhook URL for a {@code (tenantId, strategyId)} pair, preferring an
 * operator-editable plaintext field in the orchestrator {@code strategy_config} table so that
 * editing the webhook on the dashboard {@code /config} page reroutes alerts live (the DB is read on
 * the alert path, not the file mount the trading path reads).
 *
 * <p><b>Resolution order</b> (first non-blank wins):
 *
 * <ol>
 *   <li>the DB field {@code config->>'alert_webhook_url'} on the {@code strategy_config} row for
 *       {@code (tenant_id, strategy_id)} — editable on the dashboard, takes effect within one cache
 *       TTL;
 *   <li>the per-tenant env map ({@code alert.discord.webhook-urls} / {@code
 *       ALERT_DISCORD_WEBHOOK_URLS}, {@code ;}-separated {@code tenant=url} entries) parsed once at
 *       construction;
 *   <li>the global default ({@code alert.discord.webhook-url} / {@code ALERT_DISCORD_WEBHOOK_URL}).
 * </ol>
 *
 * <p>The webhook URL is treated as a NON-SECRET, plaintext config value by deliberate operator
 * decision (#418 / the dashboard /config epic): it is not envelope-encrypted, lives as a free-form
 * JSON key in the {@code config} column, and is freely editable (SAFE class — unlisted by {@code
 * StrategyConfigWriter.checkFieldClasses}). Even so, this resolver NEVER logs the resolved
 * URL/token — only the tenant/strategy and which source supplied it.
 *
 * <p><b>Best-effort, never throws.</b> A DB error (outage, missing table, broken DSL) is caught and
 * logged at WARN, then resolution falls through to the env map / global default. A blank result is
 * a valid outcome (the downstream {@link WebhookClient} treats a blank URL as a no-op). This
 * mirrors the #297 lesson: a notification-config lookup must never become a trading-path failure
 * mode.
 *
 * <p><b>Caching.</b> Per {@code (tenant, strategy)} the resolved DB lookup is cached for {@link
 * #cacheTtl} (default 30s) so a high-volume signal feed is not a DB round-trip per alert. A ~30s
 * stale window after a dashboard edit is acceptable for a notification reroute. The cache is keyed
 * on the DB lookup only; the env-map/global fallback is constant and needs no caching.
 */
@Component
public class TenantWebhookResolver {

  private static final Logger log = LoggerFactory.getLogger(TenantWebhookResolver.class);

  /** Where a resolved URL came from — logged (never the URL itself) for operator visibility. */
  enum Source {
    DB_CONFIG,
    ENV_MAP,
    GLOBAL_DEFAULT,
    NONE
  }

  /** Global default webhook (back-compat); the final fallback. */
  private final String globalWebhookUrl;

  /** Immutable per-tenant routing map (tenantId → url), parsed once at construction. */
  private final Map<String, String> webhookUrlsByTenant;

  /** May be {@code null} in test/boot envs without a DataSource — handled fail-soft like reads. */
  private final DSLContext dsl;

  private final Duration cacheTtl;

  /** (tenant|strategy) → cached DB-config lookup. {@code ConcurrentHashMap} for the alert path. */
  private final Map<String, CacheEntry> dbCache = new ConcurrentHashMap<>();

  @Autowired
  public TenantWebhookResolver(
      @Value("${alert.discord.webhook-url:}") String globalWebhookUrl,
      @Value("${alert.discord.webhook-urls:}") String webhookUrls,
      @Autowired(required = false) DSLContext dsl) {
    this(globalWebhookUrl, webhookUrls, dsl, Duration.ofSeconds(30));
  }

  /**
   * Explicit-dependency constructor (also the test seam): inject the global URL, the raw env-map
   * string, a (possibly {@code null} or broken) {@link DSLContext}, and the cache TTL directly.
   */
  public TenantWebhookResolver(
      String globalWebhookUrl, String webhookUrls, DSLContext dsl, Duration cacheTtl) {
    this.globalWebhookUrl = globalWebhookUrl == null ? "" : globalWebhookUrl;
    this.webhookUrlsByTenant = parseWebhookUrls(webhookUrls);
    this.dsl = dsl;
    this.cacheTtl = cacheTtl;
  }

  /**
   * Resolves the webhook URL for {@code (tenantId, strategyId)} per the documented order. Never
   * throws; returns the empty string when nothing is configured (a no-op downstream). The resolved
   * URL is NEVER logged — only the tenant/strategy and the {@link Source}.
   *
   * @param tenantId the tenant (null/blank skips the DB + env-map lookups → global default)
   * @param strategyId the strategy (null/blank skips the DB lookup → env-map/global)
   * @return the resolved webhook URL, or {@code ""} when none is configured
   */
  public String resolve(String tenantId, String strategyId) {
    String dbUrl = lookupDbConfig(tenantId, strategyId);
    if (isNonBlank(dbUrl)) {
      logSource(Source.DB_CONFIG, tenantId, strategyId);
      return dbUrl.trim();
    }

    String envUrl = tenantId == null ? null : webhookUrlsByTenant.get(tenantId);
    if (isNonBlank(envUrl)) {
      logSource(Source.ENV_MAP, tenantId, strategyId);
      return envUrl;
    }

    if (isNonBlank(globalWebhookUrl)) {
      logSource(Source.GLOBAL_DEFAULT, tenantId, strategyId);
      return globalWebhookUrl;
    }

    logSource(Source.NONE, tenantId, strategyId);
    return "";
  }

  /**
   * Returns the cached DB {@code alert_webhook_url} for {@code (tenant, strategy)}, querying at
   * most once per {@link #cacheTtl}. Returns {@code null} (treated as "not configured in DB") when
   * the tenant/strategy is blank, the DSL is absent, the row/field is absent, or the query errors.
   * A DB error logs WARN (tenant/strategy only) and is swallowed so resolution falls through.
   */
  private String lookupDbConfig(String tenantId, String strategyId) {
    if (dsl == null || !isNonBlank(tenantId) || !isNonBlank(strategyId)) {
      return null;
    }
    String key = tenantId + "|" + strategyId;
    CacheEntry cached = dbCache.get(key);
    long now = System.currentTimeMillis();
    if (cached != null && now - cached.fetchedAtMillis() < cacheTtl.toMillis()) {
      return cached.url();
    }
    String url = queryDbConfig(tenantId, strategyId);
    dbCache.put(key, new CacheEntry(url, now));
    return url;
  }

  /** The single DB round-trip; fail-soft (any error → {@code null}, WARN without the URL). */
  private String queryDbConfig(String tenantId, String strategyId) {
    try {
      Record row =
          dsl.fetchOne(
              "SELECT config->>'alert_webhook_url' AS webhook_url "
                  + "FROM strategy_config WHERE tenant_id = ? AND strategy_id = ?",
              tenantId,
              strategyId);
      if (row == null) {
        return null;
      }
      String url = row.get("webhook_url", String.class);
      return isNonBlank(url) ? url : null;
    } catch (RuntimeException e) {
      log.warn(
          "alert-webhook resolve: strategy_config lookup failed tenant={} strategy={} "
              + "(falling through to env-map/global)",
          tenantId,
          strategyId,
          e);
      return null;
    }
  }

  /**
   * Parses the per-tenant map source ({@code tenant=url} entries, {@code ;}-separated, split on the
   * FIRST {@code =} so a URL may itself contain {@code =}) into an immutable {@code tenantId → url}
   * map. Trims keys/values; skips blank or malformed entries with a single WARN that logs ONLY the
   * offending tenant key or a count — NEVER the URL/token. Never throws. {@code null}/blank →
   * empty.
   */
  private static Map<String, String> parseWebhookUrls(String raw) {
    Map<String, String> parsed = new LinkedHashMap<>();
    if (raw == null || raw.isBlank()) {
      return Map.copyOf(parsed);
    }
    int skipped = 0;
    StringBuilder skippedKeys = new StringBuilder();
    for (String entry : raw.split(";")) {
      String trimmed = entry.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int eq = trimmed.indexOf('=');
      if (eq <= 0) {
        // Missing '=' or a blank key (leading '='). Do NOT log the value — it may carry a token.
        skipped++;
        continue;
      }
      String tenant = trimmed.substring(0, eq).trim();
      String url = trimmed.substring(eq + 1).trim();
      if (tenant.isEmpty() || url.isEmpty()) {
        skipped++;
        if (!tenant.isEmpty()) {
          if (skippedKeys.length() > 0) {
            skippedKeys.append(',');
          }
          skippedKeys.append(tenant);
        }
        continue;
      }
      parsed.put(tenant, url);
    }
    if (skipped > 0) {
      log.warn(
          "alert-webhook webhook-urls: skipped {} malformed entr{} (tenant keys: {})",
          skipped,
          skipped == 1 ? "y" : "ies",
          skippedKeys.length() == 0 ? "n/a" : skippedKeys.toString());
    }
    return Map.copyOf(parsed);
  }

  private static boolean isNonBlank(String s) {
    return s != null && !s.isBlank();
  }

  private static void logSource(Source source, String tenantId, String strategyId) {
    // NEVER log the URL/token — only the source + tenant/strategy.
    log.debug(
        "alert-webhook resolved source={} tenant={} strategy={}", source, tenantId, strategyId);
  }

  /** A cached DB lookup result (URL may be {@code null} = "not configured in DB"). */
  private record CacheEntry(String url, long fetchedAtMillis) {}
}
