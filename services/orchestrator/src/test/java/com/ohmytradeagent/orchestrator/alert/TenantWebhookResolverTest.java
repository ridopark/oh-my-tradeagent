package com.ohmytradeagent.orchestrator.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TenantWebhookResolver}'s resolution order (DB {@code
 * strategy_config.alert_webhook_url} → env-map → global default), its fail-soft DB behavior, and
 * its short-TTL cache. All URLs here are fake — NO real Discord webhook ever appears in the repo.
 */
class TenantWebhookResolverTest {

  // Fake, obviously-not-real placeholders (never a real Discord webhook).
  private static final String DB_URL = "https://example.test/webhook/db";
  private static final String ENV_URL = "https://example.test/webhook/env";
  private static final String GLOBAL_URL = "https://example.test/webhook/global";

  private static final String ENV_MAP = "dev=" + ENV_URL;
  private static final Duration TTL = Duration.ofSeconds(30);

  /**
   * Builds a DSL whose {@code fetchOne(String, Object...)} returns a row carrying {@code dbValue}
   * (or a null row when {@code dbValue} is null). Uses an {@code Answer} so the varargs invocation
   * is matched regardless of how Mockito spreads the bind parameters.
   */
  private static DSLContext dslReturning(String dbValue) {
    DSLContext dsl = mock(DSLContext.class);
    final Record row;
    if (dbValue == null) {
      row = null;
    } else {
      row = mock(Record.class);
      org.mockito.Mockito.doReturn(dbValue).when(row).get(eq("webhook_url"), eq(String.class));
    }
    org.mockito.Mockito.doAnswer(inv -> row)
        .when(dsl)
        .fetchOne(any(String.class), any(Object.class), any(Object.class));
    return dsl;
  }

  @Test
  void dbFieldPresent_isUsedOverEnvAndGlobal() {
    TenantWebhookResolver resolver =
        new TenantWebhookResolver(GLOBAL_URL, ENV_MAP, dslReturning(DB_URL), TTL);

    assertThat(resolver.resolve("dev", "copytrade-v1")).isEqualTo(DB_URL);
  }

  @Test
  void dbFieldBlank_fallsThroughToEnvMap() {
    // A blank/absent DB field (row present, value null) → env-map per-tenant URL.
    TenantWebhookResolver resolver =
        new TenantWebhookResolver(GLOBAL_URL, ENV_MAP, dslReturning(null), TTL);

    assertThat(resolver.resolve("dev", "copytrade-v1")).isEqualTo(ENV_URL);
  }

  @Test
  void dbAndEnvAbsent_fallsThroughToGlobal() {
    // No env entry for this tenant and no DB field → global default.
    TenantWebhookResolver resolver =
        new TenantWebhookResolver(GLOBAL_URL, "other=" + ENV_URL, dslReturning(null), TTL);

    assertThat(resolver.resolve("dev", "copytrade-v1")).isEqualTo(GLOBAL_URL);
  }

  @Test
  void allBlank_resolvesToEmptyNoOp() {
    TenantWebhookResolver resolver = new TenantWebhookResolver("", "", dslReturning(null), TTL);

    assertThat(resolver.resolve("dev", "copytrade-v1")).isEmpty();
  }

  @Test
  void nullDslSkipsDbLookup_usesEnvMap() {
    // No DataSource wired (boot/test env): the DB step is skipped entirely, env-map wins.
    TenantWebhookResolver resolver = new TenantWebhookResolver(GLOBAL_URL, ENV_MAP, null, TTL);

    assertThat(resolver.resolve("dev", "copytrade-v1")).isEqualTo(ENV_URL);
  }

  @Test
  void dbThrows_fallsThroughWithoutThrowing() {
    DSLContext dsl = mock(DSLContext.class);
    when(dsl.fetchOne(any(String.class), any(Object.class), any(Object.class)))
        .thenThrow(new RuntimeException("db down"));
    TenantWebhookResolver resolver = new TenantWebhookResolver(GLOBAL_URL, ENV_MAP, dsl, TTL);

    String[] resolved = new String[1];
    assertThatCode(() -> resolved[0] = resolver.resolve("dev", "copytrade-v1"))
        .doesNotThrowAnyException();
    // DB error → fall through to env-map (then global). Never throws.
    assertThat(resolved[0]).isEqualTo(ENV_URL);
  }

  @Test
  void blankTenantOrStrategy_skipsDbLookup() {
    DSLContext dsl = dslReturning(DB_URL);
    TenantWebhookResolver resolver = new TenantWebhookResolver(GLOBAL_URL, "", dsl, TTL);

    // Blank strategy → no DB query; falls to global (no env entry).
    assertThat(resolver.resolve("dev", "")).isEqualTo(GLOBAL_URL);
    assertThat(resolver.resolve(null, "copytrade-v1")).isEqualTo(GLOBAL_URL);
    verify(dsl, times(0)).fetchOne(any(String.class), any(Object.class), any(Object.class));
  }

  @Test
  void cacheHit_doesNotRequeryWithinTtl() {
    DSLContext dsl = dslReturning(DB_URL);
    TenantWebhookResolver resolver =
        new TenantWebhookResolver(GLOBAL_URL, ENV_MAP, dsl, Duration.ofSeconds(30));

    assertThat(resolver.resolve("dev", "copytrade-v1")).isEqualTo(DB_URL);
    assertThat(resolver.resolve("dev", "copytrade-v1")).isEqualTo(DB_URL);
    assertThat(resolver.resolve("dev", "copytrade-v1")).isEqualTo(DB_URL);

    // Three resolves, one DB round-trip — the 30s cache absorbs the rest.
    verify(dsl, times(1)).fetchOne(any(String.class), any(Object.class), any(Object.class));
  }

  @Test
  void cacheExpiry_requeriesAfterTtl() {
    DSLContext dsl = dslReturning(DB_URL);
    // Zero TTL: every resolve re-queries (no stale window).
    TenantWebhookResolver resolver =
        new TenantWebhookResolver(GLOBAL_URL, ENV_MAP, dsl, Duration.ZERO);

    resolver.resolve("dev", "copytrade-v1");
    resolver.resolve("dev", "copytrade-v1");

    verify(dsl, times(2)).fetchOne(any(String.class), any(Object.class), any(Object.class));
  }
}
