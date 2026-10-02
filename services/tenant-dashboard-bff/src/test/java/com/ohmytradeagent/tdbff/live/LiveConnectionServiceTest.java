package com.ohmytradeagent.tdbff.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.tdbff.proximity.MarketDataLivenessClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code /api/live/connection} parts: each independently fail-soft, concurrent, time-bounded, and
 * mapped to ok/stale/down/unknown with the documented thresholds.
 */
@SuppressWarnings("unchecked")
class LiveConnectionServiceTest {

  // Wednesday 2026-09-30 10:00 ET (14:00Z, EDT) — inside regular trading hours.
  private static final Instant RTH = Instant.parse("2026-09-30T14:00:00Z");

  private final MarketDataLivenessClient md = mock(MarketDataLivenessClient.class);
  private final FillListenerStatusClient exec = mock(FillListenerStatusClient.class);
  private final DiscordHealthClient discord = mock(DiscordHealthClient.class);
  private final CountDownLatch hang = new CountDownLatch(1);
  private ExecutorService pool;

  @BeforeEach
  void setUp() {
    pool = Executors.newFixedThreadPool(4);
  }

  @AfterEach
  void tearDown() {
    hang.countDown();
    pool.shutdownNow();
  }

  private LiveConnectionService service(Instant now, Duration timeout) {
    return new LiveConnectionService(
        md, exec, discord, Clock.fixed(now, ZoneOffset.UTC), pool, timeout);
  }

  private LiveConnectionService service() {
    return service(RTH, Duration.ofSeconds(2));
  }

  private static Map<String, Object> feeds(boolean equityUp, long equityAgeMs, boolean optionUp) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("status", "ok");
    m.put("equity", Map.of("connected", equityUp, "lastTickAgeMs", equityAgeMs));
    m.put("option", Map.of("connected", optionUp, "lastTickAgeMs", -1L));
    return m;
  }

  /**
   * api-gateway's answer: exec's status narrowed to the asking tenant. {@code rows} are what exec
   * reported for the whole pod; like api-gateway, only {@code tenant}'s row is passed on.
   */
  private static Map<String, Object> listener(String tenant, Map<String, Object>... rows) {
    return Map.of(
        "enabled",
        true,
        "now",
        "2026-09-30T14:00:00Z",
        "broker_target",
        "alpaca-live",
        "pod_tenant_count",
        rows.length,
        "tenants",
        List.of(rows).stream().filter(r -> tenant.equals(r.get("tenant_id"))).toList());
  }

  private static Map<String, Object> row(
      String tenant, boolean connected, boolean confirmed, Double age) {
    Map<String, Object> r = new HashMap<>();
    r.put("tenant_id", tenant);
    r.put("connected", connected);
    r.put("subscription_confirmed", confirmed);
    r.put("last_event_age_s", age);
    r.put("reconnects", 3);
    r.put("metrics_scope", "pod");
    return r;
  }

  private static Map<String, Object> part(Map<String, Object> body, String key) {
    return (Map<String, Object>) body.get(key);
  }

  private static Map<String, Object> mdPart(Map<String, Object> body, String feed) {
    return (Map<String, Object>) part(body, "market_data").get(feed);
  }

  @Test
  void allHealthy_shapeAndStatuses() {
    when(md.feedHealth()).thenReturn(feeds(true, 1500L, true));
    when(exec.status("acme")).thenReturn(listener("acme", row("acme", true, true, 4.0)));
    when(discord.health()).thenReturn(Map.of("heartbeat_age_s", 3.2));

    Map<String, Object> body = service().connection("acme");

    assertThat(body.get("server_time")).isEqualTo("2026-09-30T14:00:00Z");
    assertThat(body.get("market_open")).isEqualTo(true);
    assertThat(mdPart(body, "equity"))
        .containsEntry("status", "ok")
        .containsEntry("age_s", 1.5)
        .containsEntry("connected", true);
    // -1 = no tick yet: connected, but nothing proves data flows -> unknown, never green.
    assertThat(mdPart(body, "option"))
        .containsEntry("status", "unknown")
        .containsEntry("age_s", null)
        .containsEntry("reason", "connected, no ticks yet");
    assertThat(part(body, "broker"))
        .containsEntry("status", "ok")
        .containsEntry("age_s", 4.0)
        .containsEntry("broker_target", "alpaca-live")
        .containsEntry("connected", true)
        .containsEntry("subscription_confirmed", true)
        .containsEntry("reconnects", 3)
        .containsEntry("metrics_scope", "pod");
    assertThat(part(body, "discord")).containsEntry("status", "ok").containsEntry("age_s", 3.2);
  }

  @Test
  void feedDisconnected_isDown_andMarketDataUnreachable_isUnknown() {
    when(md.feedHealth()).thenReturn(feeds(false, 90_000L, true));
    Map<String, Object> body = service().connection("acme");
    assertThat(mdPart(body, "equity")).containsEntry("status", "down");

    when(md.feedHealth()).thenReturn(Map.of("status", "unknown"));
    body = service().connection("acme");
    assertThat(mdPart(body, "equity")).containsEntry("status", "unknown");
    assertThat(mdPart(body, "option")).containsEntry("status", "unknown");
    assertThat(mdPart(body, "option").get("reason")).isNotNull();
  }

  private static Map<String, Object> equityPart(Boolean connected, Long ageMs) {
    Map<String, Object> f = new HashMap<>();
    f.put("connected", connected);
    f.put("lastTickAgeMs", ageMs);
    Map<String, Object> fh = new HashMap<>();
    fh.put("status", "ok");
    fh.put("equity", f);
    fh.put("option", f);
    return (Map<String, Object>) LiveConnectionService.feedParts(fh).get("equity");
  }

  @Test
  void feedMapping_gradesByTickAge_notConnectionAlone() {
    // Lazily-opened stock socket never opened (prod shape): idle, not down.
    assertThat(equityPart(false, -1L))
        .containsEntry("status", "unknown")
        .containsEntry("age_s", null)
        .containsEntry("reason", "idle (no stream subscribers)");
    assertThat(equityPart(false, null))
        .containsEntry("status", "unknown")
        .containsEntry("reason", "idle (no stream subscribers)");
    assertThat(equityPart(true, 30_000L))
        .containsEntry("status", "ok")
        .containsEntry("age_s", 30.0);
    assertThat(equityPart(true, 30_001L))
        .containsEntry("status", "stale")
        .containsEntry("age_s", 30.001);
    assertThat(equityPart(true, -1L))
        .containsEntry("status", "unknown")
        .containsEntry("age_s", null)
        .containsEntry("reason", "connected, no ticks yet");
    assertThat(equityPart(false, 5_000L))
        .containsEntry("status", "down")
        .containsEntry("age_s", 5.0);
  }

  @Test
  void podWideConfirmation_withSeveralTenantRows_isNeverGreen() {
    // subscription_confirmed is pod-wide: with another tenant on the pod it cannot prove ours.
    assertThat(
            LiveConnectionService.brokerPart(
                listener("acme", row("other", true, true, 1.0), row("acme", true, true, 2.0)),
                "acme"))
        .containsEntry("status", "stale")
        .containsEntry("reason", "subscription confirmation is pod-wide")
        .containsEntry("age_s", 2.0);
    // Exactly one tenant row: the pod-wide signal is this tenant's own.
    assertThat(
            LiveConnectionService.brokerPart(
                listener("acme", row("acme", true, true, 2.0)), "acme"))
        .containsEntry("status", "ok");
    // Several rows, ours disconnected: still down.
    assertThat(
            LiveConnectionService.brokerPart(
                listener("acme", row("other", true, true, 1.0), row("acme", false, true, null)),
                "acme"))
        .containsEntry("status", "down");
    // pod_tenant_count absent: cannot prove we are alone on the pod -> never green.
    Map<String, Object> noCount = new HashMap<>(listener("acme", row("acme", true, true, 2.0)));
    noCount.remove("pod_tenant_count");
    assertThat(LiveConnectionService.brokerPart(noCount, "acme")).containsEntry("status", "stale");
  }

  /**
   * New exec shape: each row carries {@code subscription_scope:"socket"}, so its {@code
   * subscription_confirmed} is THIS tenant's current socket — the pod-wide cap no longer applies.
   * The old shape ({@link #row}, no subscription_scope) is still pinned stale above: exec-alpaca-
   * live is rolled by hand, so the BFF reads the old shape for a while after it rolls.
   */
  @Test
  void perSocketConfirmation_isTrustedEvenWithSeveralTenantRows() {
    // (a) confirmed on our own socket, four tenants on the pod -> ok.
    assertThat(
            LiveConnectionService.brokerPart(
                listener(
                    "acme",
                    socketRow("a", true, true),
                    socketRow("b", true, true),
                    socketRow("c", true, false),
                    socketRow("acme", true, true)),
                "acme"))
        .containsEntry("status", "ok")
        .containsEntry("subscription_scope", "socket");
    // pod_tenant_count absent is irrelevant once the confirmation is per socket.
    Map<String, Object> noCount = new HashMap<>(listener("acme", socketRow("acme", true, true)));
    noCount.remove("pod_tenant_count");
    assertThat(LiveConnectionService.brokerPart(noCount, "acme")).containsEntry("status", "ok");
    // (b) our socket connected but not acked, even though another tenant's is -> stale.
    assertThat(
            LiveConnectionService.brokerPart(
                listener("acme", socketRow("other", true, true), socketRow("acme", true, false)),
                "acme"))
        .containsEntry("status", "stale")
        .containsEntry("reason", "subscription not confirmed");
    // (d) not connected -> down.
    assertThat(
            LiveConnectionService.brokerPart(
                listener("acme", socketRow("other", true, true), socketRow("acme", false, false)),
                "acme"))
        .containsEntry("status", "down");
  }

  /** Exec's row as of the per-socket change: {@link #row} plus {@code subscription_scope}. */
  private static Map<String, Object> socketRow(
      String tenant, boolean connected, boolean confirmed) {
    Map<String, Object> r = row(tenant, connected, confirmed, 2.0);
    r.put("subscription_scope", "socket");
    return r;
  }

  @Test
  void discordThresholds() {
    assertThat(LiveConnectionService.discordPart(Map.of("heartbeat_age_s", 14.9)))
        .containsEntry("status", "ok");
    assertThat(LiveConnectionService.discordPart(Map.of("heartbeat_age_s", 15.0)))
        .containsEntry("status", "stale");
    assertThat(LiveConnectionService.discordPart(Map.of("heartbeat_age_s", 119.9)))
        .containsEntry("status", "stale");
    assertThat(LiveConnectionService.discordPart(Map.of("heartbeat_age_s", 120.0)))
        .containsEntry("status", "down");
    Map<String, Object> nullAge = new HashMap<>();
    nullAge.put("heartbeat_age_s", null);
    assertThat(LiveConnectionService.discordPart(nullAge))
        .containsEntry("status", "down")
        .containsEntry("age_s", null);
  }

  @Test
  void discordUnreachable_isUnknown() {
    when(md.feedHealth()).thenReturn(feeds(true, 0L, true));
    when(exec.status("acme")).thenReturn(listener("acme", row("acme", true, true, 1.0)));
    when(discord.health()).thenThrow(new RuntimeException("connection refused"));

    Map<String, Object> d = part(service().connection("acme"), "discord");

    assertThat(d).containsEntry("status", "unknown");
    assertThat(d.get("reason")).isNotNull();
  }

  @Test
  void brokerMapping_andTenantFiltering() {
    // Only THIS tenant's row is read, even if an unfiltered list ever arrived.
    Map<String, Object> unfiltered = new HashMap<>(listener("acme"));
    unfiltered.put("pod_tenant_count", 2);
    unfiltered.put(
        "tenants", List.of(row("other", true, true, 1.0), row("acme", false, false, null)));
    assertThat(LiveConnectionService.brokerPart(unfiltered, "acme"))
        .containsEntry("status", "down")
        .doesNotContainValue("other");

    assertThat(
            LiveConnectionService.brokerPart(
                listener("acme", row("acme", true, false, 2.0)), "acme"))
        .containsEntry("status", "stale");
    assertThat(
            LiveConnectionService.brokerPart(
                listener("acme", row("other", true, true, 1.0)), "acme"))
        .containsEntry("status", "unknown");
    assertThat(
            LiveConnectionService.brokerPart(
                Map.of("enabled", false, "tenants", List.of()), "acme"))
        .containsEntry("status", "unknown");
  }

  @Test
  void apiGatewayUnknown_isUnknownWithReason_neverGreenOrRed() {
    when(md.feedHealth()).thenReturn(feeds(true, 0L, true));
    when(exec.status("acme"))
        .thenReturn(
            Map.of(
                "status", "unknown",
                "reason", "api-gateway: exec status read failed",
                "broker_target", "alpaca-live"));
    when(discord.health()).thenReturn(Map.of("heartbeat_age_s", 1.0));

    assertThat(part(service().connection("acme"), "broker"))
        .containsEntry("status", "unknown")
        .containsEntry("reason", "api-gateway: exec status read failed")
        .containsEntry("broker_target", "alpaca-live");
  }

  @Test
  void brokerTargetComesFromApiGateway() {
    when(md.feedHealth()).thenReturn(feeds(true, 0L, true));
    Map<String, Object> paper = new HashMap<>(listener("acme", row("acme", true, true, 1.0)));
    paper.put("broker_target", "alpaca-paper");
    when(exec.status("acme")).thenReturn(paper);
    when(discord.health()).thenReturn(Map.of("heartbeat_age_s", 1.0));

    assertThat(part(service().connection("acme"), "broker"))
        .containsEntry("status", "ok")
        .containsEntry("broker_target", "alpaca-paper");
  }

  @Test
  void onePartThrows_othersStillAnswer() {
    when(md.feedHealth()).thenReturn(feeds(true, 0L, true));
    when(exec.status("acme")).thenThrow(new RuntimeException("exec unreachable"));
    when(discord.health()).thenReturn(Map.of("heartbeat_age_s", 1.0));

    Map<String, Object> body = service().connection("acme");

    assertThat(part(body, "broker")).containsEntry("status", "unknown");
    assertThat(part(body, "broker").get("reason")).isNotNull();
    assertThat(mdPart(body, "equity")).containsEntry("status", "ok");
    assertThat(part(body, "discord")).containsEntry("status", "ok");
  }

  @Test
  void onePartHangsPastTimeout_othersStillAnswer_totalTimeBounded() {
    when(md.feedHealth())
        .thenAnswer(
            inv -> {
              hang.await(30, TimeUnit.SECONDS);
              return feeds(true, 0L, true);
            });
    when(exec.status("acme")).thenReturn(listener("acme", row("acme", true, true, 1.0)));
    when(discord.health()).thenReturn(Map.of("heartbeat_age_s", 1.0));

    long start = System.nanoTime();
    Map<String, Object> body = service().connection("acme");
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(elapsedMs).isLessThan(2_500L);
    assertThat(mdPart(body, "equity")).containsEntry("status", "unknown");
    assertThat(mdPart(body, "option")).containsEntry("status", "unknown");
    assertThat(part(body, "broker")).containsEntry("status", "ok");
    assertThat(part(body, "discord")).containsEntry("status", "ok");
  }

  @Test
  void marketOpen_mirrorsMarketDataRthRule() {
    when(md.feedHealth()).thenReturn(Map.of("status", "unknown"));
    // 09:29 ET weekday -> closed; 09:30 -> open; 16:00 -> closed; Saturday noon -> closed.
    assertThat(
            service(Instant.parse("2026-09-30T13:29:00Z"), Duration.ofSeconds(2))
                .connection("acme")
                .get("market_open"))
        .isEqualTo(false);
    assertThat(
            service(Instant.parse("2026-09-30T13:30:00Z"), Duration.ofSeconds(2))
                .connection("acme")
                .get("market_open"))
        .isEqualTo(true);
    assertThat(
            service(Instant.parse("2026-09-30T20:00:00Z"), Duration.ofSeconds(2))
                .connection("acme")
                .get("market_open"))
        .isEqualTo(false);
    assertThat(
            service(Instant.parse("2026-10-03T16:00:00Z"), Duration.ofSeconds(2))
                .connection("acme")
                .get("market_open"))
        .isEqualTo(false);
  }
}
