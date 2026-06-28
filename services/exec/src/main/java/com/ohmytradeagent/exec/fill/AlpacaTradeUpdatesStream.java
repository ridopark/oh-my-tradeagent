package com.ohmytradeagent.exec.fill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.exec.broker.BrokerCredentialSource;
import com.ohmytradeagent.exec.broker.BrokerCredentials;
import com.ohmytradeagent.exec.broker.alpaca.AlpacaProperties;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Long-running Alpaca trade-updates WebSocket listener. Connects to the configured stream URL,
 * sends the {@code authenticate} + {@code listen} handshake frames, parses incoming JSON, filters
 * to {@code fill} / {@code partial_fill}, dedupes on {@code (broker_order_id, filled_qty)}, and
 * hands each surviving event to a {@link FillDispatcher}.
 *
 * <p><b>Single-socket vs. per-tenant.</b> Default ({@code exec.fill-listener.per-tenant.enabled =
 * false}) opens ONE pod-wide socket on {@code props.wsUrl()} authenticated with the pod-wide env
 * creds — byte-identical to the pre-Phase-G behavior. With the flag TRUE, {@link #start()}
 * enumerates {@link BrokerCredentialSource#liveTenants} and opens ONE independently-supervised
 * socket per live tenant, each authenticating with THAT tenant's resolved credentials against that
 * tenant's resolved WS URL. Each tenant has its own thread, backoff state, current-socket handle,
 * and dedup map, so one tenant's disconnect/backoff never affects another's.
 *
 * <p><b>Single-pod constraint.</b> This component is NOT leader-elected. The exec service must
 * deploy with {@code replicas: 1}; N sockets across M pods would multiply dispatch by M.
 *
 * <p>Lifecycle: starts in daemon threads on {@link ApplicationReadyEvent} so Temporal worker
 * registration completes first; stops on bean destruction.
 */
// Gate on BOTH flags so an operator who flips fill-listener on with broker.impl=stub gets a clean
// "bean disabled, condition not met" startup message instead of a cryptic
// NoSuchBeanDefinitionException
// on AlpacaProperties (which AlpacaConfig only registers for an alpaca-* broker.impl).
@Component
@ConditionalOnExpression(
    "'${broker.impl:}'.startsWith('alpaca-') and ${exec.fill-listener.enabled:false}")
@EnableConfigurationProperties(FillListenerProperties.class)
public class AlpacaTradeUpdatesStream {

  private static final Logger log = LoggerFactory.getLogger(AlpacaTradeUpdatesStream.class);
  private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(2);
  private static final String PROVIDER = "alpaca";

  /**
   * Upper bound on a single accumulated text frame. Alpaca fills are sub-kilobyte; a runaway
   * continuation frame past this size aborts the socket and lets the reconnect loop recover rather
   * than letting the listener OOM.
   */
  private static final int MAX_FRAME_BYTES = 1 << 20;

  private final FillListenerProperties props;
  private final AlpacaProperties alpacaProps;
  private final BrokerCredentialSource credentialSource;
  private final FillDispatcher dispatcher;
  private final FillListenerMetrics metrics;
  private final ObjectMapper mapper;
  private final HttpClient http;

  private volatile boolean stopped;
  private final List<TenantRunner> runners = new ArrayList<>();

  public AlpacaTradeUpdatesStream(
      FillListenerProperties props,
      AlpacaProperties alpacaProps,
      BrokerCredentialSource credentialSource,
      FillDispatcher dispatcher,
      FillListenerMetrics metrics,
      ObjectMapper mapper) {
    this.props = props;
    this.alpacaProps = alpacaProps;
    this.credentialSource = credentialSource;
    this.dispatcher = dispatcher;
    this.metrics = metrics;
    this.mapper = mapper;
    this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    if (props.perTenantEnabled()) {
      startPerTenant();
    } else {
      startSingleSocket();
    }
  }

  /**
   * Pre-Phase-G path: ONE pod-wide socket on {@code props.wsUrl()} authenticated with the pod-wide
   * env creds ({@code alpacaProps}). The resolved {@link Endpoint} is fixed for the pod's life, so
   * a reconnect re-uses the same creds/url — byte-identical to the original single-socket listener.
   */
  private void startSingleSocket() {
    TenantRunner runner =
        new TenantRunner(
            "pod-wide",
            "fill-listener-ws",
            () -> new Endpoint(props.wsUrl(), alpacaProps.apiKeyId(), alpacaProps.apiSecretKey()));
    runners.add(runner);
    runner.start();
    log.info("fill-listener started (single-socket) ws_url={}", props.wsUrl());
  }

  /**
   * Phase G path: one independently-supervised socket per enumerated live tenant. Each runner
   * re-resolves its tenant's credentials on every (re)connect so a credential rotation takes effect
   * without a restart. A tenant whose creds are missing/blank is SKIPPED with an error log rather
   * than entering a blank-auth reconnect storm (a silent live-fill outage). Enumeration is
   * startup-only: a tenant added at runtime is picked up by the 30s {@code FillPoller} fallback
   * and, for real-time fills, requires a manual exec roll — a deliberate deferral over a periodic
   * re-enumeration loop.
   */
  private void startPerTenant() {
    // TreeSet → deterministic startup order; the roster is per-pod-broker-target-scoped by
    // construction (the source can only resolve this pod's account(s)).
    Set<String> tenants = new TreeSet<>(credentialSource.liveTenants(PROVIDER));
    if (tenants.isEmpty()) {
      log.warn("fill-listener per-tenant enabled but credential source enumerated NO live tenants");
      return;
    }
    int started = 0;
    for (String tenant : tenants) {
      // Fail-closed pre-check: if the tenant's creds can't be resolved (or are blank) at startup,
      // skip it rather than spinning a runner that would send blank auth forever. The runner
      // re-resolves on each reconnect, so a transient resolve fault past startup is covered by
      // backoff; a hard missing-cred is logged and skipped here.
      Endpoint endpoint;
      try {
        endpoint = resolveEndpoint(tenant);
      } catch (RuntimeException e) {
        log.error(
            "fill-listener SKIPPING tenant={} — credential resolution failed at startup: {}",
            tenant,
            e.toString());
        continue;
      }
      if (isBlank(endpoint.keyId()) || isBlank(endpoint.secret())) {
        log.error(
            "fill-listener SKIPPING tenant={} — resolved credentials have a blank key/secret;"
                + " refusing to open a socket that would authenticate blank",
            tenant);
        continue;
      }
      TenantRunner runner =
          new TenantRunner(tenant, "fill-listener-ws-" + tenant, () -> resolveEndpoint(tenant));
      runners.add(runner);
      runner.start();
      started++;
    }
    log.info(
        "fill-listener started (per-tenant) tenants_enumerated={} sockets_started={}",
        tenants.size(),
        started);
  }

  /**
   * Resolves a tenant's trade-updates endpoint: WS URL + auth creds from {@link
   * BrokerCredentialSource}. Validates the resolved WS URL with the same secure-scheme rule the
   * pod-wide URL gets (the auth frame carries the key+secret, so a plaintext non-loopback {@code
   * ws://} would leak them). Called on every (re)connect, so a rotated credential is picked up for
   * free.
   */
  private Endpoint resolveEndpoint(String tenant) {
    BrokerCredentials creds = credentialSource.resolve(tenant, PROVIDER);
    String wsUrl = creds.wsUrl();
    if (isBlank(wsUrl)) {
      throw new IllegalStateException(
          "resolved credentials for tenant=" + tenant + " have no ws-url");
    }
    FillListenerProperties.requireSecureWsUrl(wsUrl);
    return new Endpoint(wsUrl, creds.apiKeyId(), creds.apiSecretKey());
  }

  @PreDestroy
  public void stop() {
    stopped = true;
    for (TenantRunner runner : runners) {
      runner.stop();
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private String serialize(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("ws frame serialization failed", e);
    }
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  /** Resolved per-(re)connect endpoint: where to connect and which creds to authenticate with. */
  private record Endpoint(String wsUrl, String keyId, String secret) {}

  /** Supplies the {@link Endpoint} for the current (re)connect attempt. */
  @FunctionalInterface
  private interface EndpointSupplier {
    Endpoint get();
  }

  /**
   * One independently-supervised socket: its own daemon thread, backoff state, current-socket
   * handle, and tenant-scoped dedup map. A broker_order_id is only unique per account, so the dedup
   * map MUST be per-tenant — sharing one map across accounts could drop a real fill on a
   * cross-account broker_order_id collision.
   */
  private final class TenantRunner {
    private final String tenant;
    private final String threadName;
    private final EndpointSupplier endpointSupplier;
    private final Map<String, Boolean> dedup;
    private final AtomicReference<WebSocket> currentSocket = new AtomicReference<>();
    private Thread runner;

    TenantRunner(String tenant, String threadName, EndpointSupplier endpointSupplier) {
      this.tenant = tenant;
      this.threadName = threadName;
      this.endpointSupplier = endpointSupplier;
      this.dedup =
          new LinkedHashMap<>(props.dedupCacheSize(), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
              return size() > props.dedupCacheSize();
            }
          };
    }

    void start() {
      runner = new Thread(this::runForever, threadName);
      runner.setDaemon(true);
      runner.start();
    }

    void stop() {
      WebSocket ws = currentSocket.getAndSet(null);
      if (ws != null) {
        try {
          ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown")
              .toCompletableFuture()
              .get(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
          log.warn("fill-listener[{}] close failed: {}", tenant, e.toString());
        }
      }
      if (runner != null) {
        runner.interrupt();
        try {
          runner.join(SHUTDOWN_TIMEOUT.toMillis());
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
    }

    void runForever() {
      long backoff = props.reconnectBaseMs();
      boolean firstAttempt = true;
      while (!stopped) {
        try {
          if (!firstAttempt) {
            metrics.recordReconnect();
          }
          firstAttempt = false;
          connectAndRun();
          // Clean return = the WS connected, served traffic, then closed normally.
          // Reset backoff so the NEXT reconnect doesn't pay for the cap from a
          // long-lived prior session.
          backoff = props.reconnectBaseMs();
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        } catch (RuntimeException e) {
          log.warn("fill-listener[{}] connect/run failed: {}", tenant, e.toString());
          backoff = Math.min(backoff * 2L, props.reconnectCapMs());
        }
        if (stopped) {
          return;
        }
        try {
          Thread.sleep(backoff);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }

    void connectAndRun() throws InterruptedException {
      Endpoint endpoint = endpointSupplier.get();
      CountDownLatch closed = new CountDownLatch(1);
      Listener listener = new Listener(closed, this);
      WebSocket ws;
      try {
        ws =
            http.newWebSocketBuilder()
                .buildAsync(URI.create(endpoint.wsUrl()), listener)
                .get(HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      } catch (ExecutionException | TimeoutException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        throw new RuntimeException("ws connect failed: " + cause, cause);
      }
      currentSocket.set(ws);
      sendAuth(ws, endpoint);
      sendListen(ws);
      closed.await();
      currentSocket.compareAndSet(ws, null);
    }

    private void sendAuth(WebSocket ws, Endpoint endpoint) throws InterruptedException {
      Map<String, Object> frame =
          Map.of(
              "action",
              "authenticate",
              "data",
              Map.of(
                  "key_id",
                  nullToEmpty(endpoint.keyId()),
                  "secret_key",
                  nullToEmpty(endpoint.secret())));
      sendTextWithTimeout(ws, serialize(frame));
    }

    private void sendListen(WebSocket ws) throws InterruptedException {
      Map<String, Object> frame =
          Map.of("action", "listen", "data", Map.of("streams", List.of("trade_updates")));
      sendTextWithTimeout(ws, serialize(frame));
    }

    private void sendTextWithTimeout(WebSocket ws, String frame) throws InterruptedException {
      try {
        ws.sendText(frame, true)
            .toCompletableFuture()
            .get(HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      } catch (ExecutionException | TimeoutException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        throw new RuntimeException("ws sendText failed: " + cause, cause);
      }
    }

    private void handleFrame(String frame) {
      JsonNode root;
      try {
        root = mapper.readTree(frame);
      } catch (Exception e) {
        log.warn("fill-listener[{}] parse failed: {}", tenant, e.toString());
        return;
      }
      JsonNode streamNode = root.get("stream");
      if (streamNode == null || !"trade_updates".equals(streamNode.asText())) {
        return;
      }
      JsonNode data = root.get("data");
      if (data == null) {
        return;
      }
      String event = data.path("event").asText("");
      metrics.markEvent();
      metrics.recordReceived(event);
      if (!"fill".equals(event) && !"partial_fill".equals(event)) {
        return;
      }
      JsonNode order = data.get("order");
      if (order == null) {
        return;
      }
      String brokerOrderId = order.path("id").asText(null);
      String clientOrderId = order.path("client_order_id").asText(null);
      long filledQty = order.path("filled_qty").asLong(0L);
      BigDecimal avgFillPrice =
          order.hasNonNull("filled_avg_price")
              ? new BigDecimal(order.get("filled_avg_price").asText())
              : null;
      OffsetDateTime filledAt =
          order.hasNonNull("filled_at")
              ? OffsetDateTime.parse(order.get("filled_at").asText())
              : null;
      if (brokerOrderId == null || avgFillPrice == null || filledAt == null) {
        log.warn(
            "fill-listener[{}] missing field broker_order_id={} avg={} at={}",
            tenant,
            brokerOrderId,
            avgFillPrice,
            filledAt);
        return;
      }
      // Tenant-scoped dedup: this map belongs to ONE tenant's socket, so the (broker_order_id,
      // filled_qty) key is implicitly account-scoped — the same broker_order_id arriving on a
      // different tenant's socket goes through that tenant's own map and is NOT cross-deduped.
      String dedupKey = brokerOrderId + "|" + filledQty;
      if (dedup.putIfAbsent(dedupKey, Boolean.TRUE) != null) {
        metrics.recordDroppedDedup();
        return;
      }
      BrokerFillEvent fill =
          new BrokerFillEvent(
              brokerOrderId,
              clientOrderId,
              filledQty,
              avgFillPrice,
              filledAt,
              BrokerFillEvent.Source.WS);
      try {
        dispatcher.dispatch(fill);
      } catch (RuntimeException e) {
        log.error("fill-listener[{}] dispatch failed broker_order_id={}", tenant, brokerOrderId, e);
        // Stream stays alive — a dispatcher fault must not blind the listener.
      }
      // events_dispatched_total is bumped inside FillDispatcherImpl after the
      // signal succeeds, so WS and poll paths share one accounting point.
    }
  }

  private final class Listener implements WebSocket.Listener {
    private final CountDownLatch closed;
    private final TenantRunner owner;
    private final StringBuilder partialFrame = new StringBuilder();

    Listener(CountDownLatch closed, TenantRunner owner) {
      this.closed = closed;
      this.owner = owner;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      if (partialFrame.length() + data.length() > MAX_FRAME_BYTES) {
        log.warn(
            "fill-listener[{}] frame exceeds {} bytes; aborting socket to recover via reconnect",
            owner.tenant,
            MAX_FRAME_BYTES);
        partialFrame.setLength(0);
        webSocket.abort();
        closed.countDown();
        return null;
      }
      partialFrame.append(data);
      if (last) {
        String frame = partialFrame.toString();
        partialFrame.setLength(0);
        owner.handleFrame(frame);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      log.info("fill-listener[{}] ws closed code={} reason={}", owner.tenant, statusCode, reason);
      closed.countDown();
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      log.warn("fill-listener[{}] ws error: {}", owner.tenant, error.toString());
      closed.countDown();
    }
  }
}
