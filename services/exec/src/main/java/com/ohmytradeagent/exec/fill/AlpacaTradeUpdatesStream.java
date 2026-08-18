package com.ohmytradeagent.exec.fill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.exec.broker.BrokerCredentialSource;
import com.ohmytradeagent.exec.broker.BrokerCredentials;
import com.ohmytradeagent.exec.broker.alpaca.AlpacaProperties;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Long-running Alpaca trade-updates WebSocket listener. Connects to the configured stream URL,
 * sends the {@code authenticate} + {@code listen} handshake frames, parses incoming JSON, filters
 * to {@code fill} / {@code partial_fill}, dedupes on {@code (broker_order_id, filled_qty)}, and
 * hands each surviving event to a {@link FillDispatcher}.
 *
 * <p><b>Frames arrive on BOTH channels.</b> Alpaca sends trade_updates as <b>binary</b> frames
 * carrying JSON, so {@link Listener#onBinary} is load-bearing, not defensive — see #693, where its
 * absence meant the JDK default silently discarded every frame for 11 weeks and the polling
 * fallback quietly covered for it. {@code onText} is retained because the channel choice is not
 * contractual and paper/live may differ; both route into the same {@code handleFrame}.
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

  /**
   * Cap on distinct unmodelled {@code stream} values warned about per connection. The values are
   * remote input, so the damping set must not grow unbounded on a server that emits novel stream
   * names.
   */
  private static final int MAX_UNHANDLED_STREAMS_WARNED = 8;

  /**
   * Sentinel occupying one slot in {@code unhandledStreamsWarned} for frames that carry no {@code
   * stream} field at all, so they share the per-connection damping and its cap. Not a legal Alpaca
   * stream name, so it cannot collide with a real one.
   */
  private static final String NO_STREAM_FIELD_KEY = "<no-stream-field>";

  /** Upper bound on field names named in a shape description. */
  private static final int MAX_SHAPE_FIELDS = 20;

  private final FillListenerProperties props;
  private final AlpacaProperties alpacaProps;
  private final BrokerCredentialSource credentialSource;
  private final FillDispatcher dispatcher;
  private final FillListenerMetrics metrics;
  private final ObjectMapper mapper;
  private final HttpClient http;

  private volatile boolean stopped;

  // {@code runners} + {@code runningTenants} are now mutated by BOTH the lifecycle thread
  // (start/stop, per-tenant mode) AND the @Scheduled re-enumeration thread. All enumerate-diff-add
  // and teardown access goes through {@code runnersLock} so a tenant can NEVER get two runners,
  // even
  // if the first scheduled tick races the initial start(). {@code runningTenants} is the
  // idempotency key: a tenant already in it is never given a second socket.
  private final Object runnersLock = new Object();
  private final List<TenantRunner> runners = new ArrayList<>();
  private final Set<String> runningTenants = new HashSet<>();

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
    synchronized (runnersLock) {
      runners.add(runner);
    }
    runner.start();
    log.info("fill-listener started (single-socket) ws_url={}", props.wsUrl());
  }

  /**
   * Phase G path: one independently-supervised socket per enumerated live tenant. Each runner
   * re-resolves its tenant's credentials on every (re)connect so a credential rotation takes effect
   * without a restart. A tenant whose creds are missing/blank is SKIPPED with an error log rather
   * than entering a blank-auth reconnect storm (a silent live-fill outage). Startup enumerates the
   * roster once; {@link #reenumerateTick()} then re-enumerates periodically (Phase 3), so a tenant
   * onboarded at runtime gets its real-time fill socket WITHOUT a manual exec roll.
   */
  private void startPerTenant() {
    // TreeSet → deterministic startup order; the roster is per-pod-broker-target-scoped by
    // construction (the source can only resolve this pod's account(s)).
    Set<String> tenants;
    try {
      tenants = new TreeSet<>(credentialSource.liveTenants(PROVIDER));
    } catch (RuntimeException e) {
      // A transient mount/DB fault while enumerating must not abort start() or leave the listener
      // in a half-state — open NO sockets and let the 30s FillPoller remain the fill fallback. (No
      // retry: enumeration is startup-only; a new live tenant implies a manual exec roll anyway.)
      log.error(
          "fill-listener per-tenant enabled but tenant enumeration failed; opening NO sockets: {}",
          e.toString());
      return;
    }
    if (tenants.isEmpty()) {
      log.warn("fill-listener per-tenant enabled but credential source enumerated NO live tenants");
      return;
    }
    int started = 0;
    for (String tenant : tenants) {
      if (resolveAndStart(tenant)) {
        started++;
      }
    }
    log.info(
        "fill-listener started (per-tenant) tenants_enumerated={} sockets_started={}",
        tenants.size(),
        started);
  }

  /**
   * Periodic re-enumeration (Phase 3): a newly-onboarded live tenant's {@code trade_updates} socket
   * opens WITHOUT a manual exec roll. Diffs the current live roster against {@link #runningTenants}
   * and starts ONE idempotent {@link TenantRunner} per newly-appeared tenant; it NEVER restarts,
   * replaces, or tears down an existing runner. Inert unless {@code perTenantEnabled} —
   * single-socket mode must stay byte-for-byte the pre-Phase-3 behavior (no enumeration, no runner
   * churn).
   *
   * <p>Deprovision is deliberately OUT OF SCOPE here: a tenant that disappears from the roster
   * keeps its runner (removing it stays a manual exec roll), so this loop only ever ADDS sockets —
   * never subtracts — which keeps it trivially safe against the {@code replicas: 1} exit-accounting
   * invariant.
   */
  @Scheduled(fixedDelayString = "${exec.fill-listener.reenumerate-delay-ms:60000}")
  public void reenumerateTick() {
    if (!props.perTenantEnabled()) {
      // Single-socket mode: enumeration was one-shot at start(); the tick is inert.
      return;
    }
    reenumerateOnce();
  }

  /**
   * One re-enumeration pass. Package-private so a unit test can drive a single deterministic tick.
   * A transient enumeration fault ({@code liveTenants} throws) is caught and the tick skipped — it
   * must never crash the scheduler thread. Per-tenant credential/endpoint faults fail closed on
   * that tenant's own runner (it is simply not started this tick and retried next tick) without
   * aborting the loop for the other newly-appeared tenants.
   *
   * <p>Lock discipline: the roster is enumerated off-lock, the candidate set (roster −
   * already-running) is snapshotted under the lock and the lock RELEASED, then each candidate's
   * (blocking) credential resolution runs OFF the lock — {@link #resolveAndStart} re-acquires the
   * lock only for the atomic membership re-check + add + start, so the critical section never spans
   * blocking DB/decrypt I/O.
   */
  void reenumerateOnce() {
    if (stopped) {
      return;
    }
    Set<String> tenants;
    try {
      tenants = new TreeSet<>(credentialSource.liveTenants(PROVIDER));
    } catch (RuntimeException e) {
      log.warn("fill-listener re-enumeration failed; skipping this tick: {}", e.toString());
      return;
    }
    // Snapshot candidates (newly-appeared tenants) under the lock, then release it so the blocking
    // per-candidate resolution below happens OFF the lock.
    List<String> candidates = new ArrayList<>();
    synchronized (runnersLock) {
      for (String tenant : tenants) {
        if (!runningTenants.contains(tenant)) {
          candidates.add(tenant);
        }
      }
    }
    int started = 0;
    for (String tenant : candidates) {
      if (resolveAndStart(tenant)) {
        started++;
      }
    }
    if (started > 0) {
      log.info(
          "fill-listener re-enumerated new_tenants={} sockets_started={}",
          candidates.size(),
          started);
    }
  }

  /**
   * Resolves a candidate tenant's endpoint OFF the lock (a blocking DB SELECT + envelope-decrypt
   * for the DB source), then commits ONE runner under {@link #runnersLock} with a fresh {@code
   * stopped} + membership re-check — the single idempotency guard shared by startup and
   * re-enumeration. Returns true iff a runner was started.
   *
   * <p>The eager pre-check resolve is deliberately OFF the lock so blocking I/O never widens the
   * critical section: a credential resolution failure (missing OR blank key/secret) skips the
   * tenant and, crucially, does NOT record it, so a later tick retries it once its creds land. A
   * tenant that resolves fine is committed under the lock; the membership re-check there closes the
   * TOCTOU window against a concurrent path (or a racing startup tick) that resolved the same
   * tenant off-lock — whichever commits first wins, the other sees {@code runningTenants.contains}
   * and backs out, so a tenant can NEVER get two runners.
   */
  private boolean resolveAndStart(String tenant) {
    try {
      resolveEndpoint(tenant); // eager pre-check, OFF the lock
    } catch (RuntimeException e) {
      log.error(
          "fill-listener SKIPPING tenant={} — credential resolution failed: {}",
          tenant,
          e.toString());
      return false;
    }
    synchronized (runnersLock) {
      if (stopped) {
        // Raced stop(): shutdown already snapshotted runners; never add a runner after teardown.
        return false;
      }
      if (runningTenants.contains(tenant)) {
        // A concurrent path already started it — NEVER open a second socket (no TOCTOU).
        return false;
      }
      TenantRunner runner =
          new TenantRunner(tenant, "fill-listener-ws-" + tenant, () -> resolveEndpoint(tenant));
      runners.add(runner);
      runningTenants.add(tenant);
      // If runner.start() throws (e.g. OOM creating the native thread), the tenant STAYS marked
      // running here: retry is blocked, which is the DELIBERATE safe direction for the
      // no-duplicate-socket invariant. That tenant simply falls back to the 30s FillPoller until an
      // exec roll, rather than risking a second socket on a later tick.
      runner.start();
      return true;
    }
  }

  /** Visible for testing: current live runner count (both single-socket and per-tenant modes). */
  int runnerCount() {
    synchronized (runnersLock) {
      return runners.size();
    }
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
    // Fail closed on BOTH startup and every reconnect: never open a socket that authenticates with
    // a
    // blank key/secret (a silent blank-auth reconnect storm). A cred rotated to blank post-startup
    // throws here → caught in the runner loop → backoff, NOT a storm.
    if (isBlank(creds.apiKeyId()) || isBlank(creds.apiSecretKey())) {
      throw new IllegalStateException(
          "resolved credentials for tenant=" + tenant + " have a blank key/secret");
    }
    FillListenerProperties.requireSecureWsUrl(wsUrl);
    return new Endpoint(wsUrl, creds.apiKeyId(), creds.apiSecretKey());
  }

  @PreDestroy
  public void stop() {
    stopped = true;
    // Snapshot under the lock (a re-enumeration tick may be adding runners concurrently), then stop
    // outside it so the blocking socket-close/thread-join never holds up a scheduler tick.
    List<TenantRunner> snapshot;
    synchronized (runnersLock) {
      snapshot = new ArrayList<>(runners);
    }
    for (TenantRunner runner : snapshot) {
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

  /**
   * Describes a frame's SHAPE — node type, and field names only — for a frame this listener does
   * not model.
   *
   * <p><b>Never emits values.</b> A frame we cannot classify is, by definition, one whose contents
   * we cannot reason about, and the handshake this listener sends carries the broker key and secret
   * ({@code sendAuth}). Dumping an unknown inbound frame verbatim to diagnose a logging gap would
   * risk writing a credential into the pod log permanently. Field names identify the envelope,
   * which is the whole diagnostic need; values add nothing and carry all of the risk.
   *
   * <p>Arrays are described by size plus the first element's fields because that is the concrete
   * shape worth catching: Alpaca's v2 streams deliver batched arrays, and {@code
   * ArrayNode.get("stream")} returns null, so such a frame lands in the caller's branch.
   */
  private static String describeShape(JsonNode node) {
    if (node.isArray()) {
      String elementFields = node.isEmpty() ? "[]" : fieldNames(node.get(0));
      return "type=ARRAY size=" + node.size() + " element_fields=" + elementFields;
    }
    return "type=" + node.getNodeType() + " fields=" + fieldNames(node);
  }

  /** Field names of an object node, capped; {@code []} for anything that is not an object. */
  private static String fieldNames(JsonNode node) {
    if (node == null || !node.isObject()) {
      return "[]";
    }
    List<String> names = new ArrayList<>();
    Iterator<String> it = node.fieldNames();
    while (it.hasNext() && names.size() < MAX_SHAPE_FIELDS) {
      names.add(it.next());
    }
    if (it.hasNext()) {
      names.add("…");
    }
    return names.toString();
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
    // Distinct unmodelled `stream` values already warned about on the CURRENT connection. Damping
    // is per-value so an unmodelled keepalive warns once instead of once per frame, and cleared on
    // every (re)connect (see connectAndRun) so a shape that appears only after a reconnect is not
    // permanently hidden by a warning emitted hours earlier.
    //
    // Everything stays at WARN rather than falling back to DEBUG: prod runs at the Spring default
    // root level of INFO, so a DEBUG line is invisible exactly when it is needed. Size-capped
    // because the values are remote input — a server emitting unbounded distinct stream names must
    // not grow this set without bound.
    private final Set<String> unhandledStreamsWarned = ConcurrentHashMap.newKeySet();
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
      // Fresh connection => re-arm the unhandled-stream warnings, so a frame shape that only shows
      // up after a reconnect is not silenced by a warning emitted on the previous socket.
      unhandledStreamsWarned.clear();
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

    // MUST NOT THROW. Both call sites (Listener#onText / #onBinary) invoke webSocket.request(1)
    // AFTER this returns, so an escaping exception skips the request and the socket stops being fed
    // frames entirely — permanently mute, strictly worse than the bug this logging exists to find.
    // Hence the `path()`-only idiom below: path() yields MissingNode rather than null, so no
    // traversal here can NPE. Never reach for `root.get(...).path(...)` in this method.
    private void handleFrame(String frame) {
      JsonNode root;
      try {
        root = mapper.readTree(frame);
      } catch (Exception e) {
        log.warn("fill-listener[{}] parse failed: {}", tenant, e.toString());
        return;
      }
      JsonNode streamNode = root.get("stream");
      if (streamNode == null) {
        // The last silent drop in this method. The unhandled-stream WARN below covers unmodelled
        // stream VALUES but sits after this check, so a frame with no top-level `stream` field at
        // all used to vanish with no log and no metric — indistinguishable from a healthy quiet
        // socket, which is the failure mode #693/#694/#720 each closed one layer of. It is the
        // leading remaining explanation for #715, where the socket is authorized AND subscribed
        // AND silent: any envelope lacking this field (a schema change, a batched array) would be
        // discarded here without trace.
        //
        // The counter is bumped on EVERY such frame; only the log is damped. Damping that hides
        // volume is how "a few odd frames" and "every fill for six hours" come to look identical.
        metrics.recordFrameWithoutStream();
        if (unhandledStreamsWarned.size() < MAX_UNHANDLED_STREAMS_WARNED
            && unhandledStreamsWarned.add(NO_STREAM_FIELD_KEY)) {
          log.warn(
              "fill-listener[{}] frame has no top-level `stream` field — envelope not modelled by"
                  + " this listener; {} (shape only, see describeShape)",
              tenant,
              describeShape(root));
        }
        return;
      }
      String streamName = streamNode.asText();
      // #693: log the handshake reply. This frame was previously dropped by the filter below along
      // with everything else that isn't trade_updates, which is WHY a mute socket was invisible —
      // there was no positive evidence of a working stream to be absent. Mirrors the stocks-WS
      // `authenticated` line from 9ec7387. Alpaca's authorization payload carries status/action/
      // message only; the request echo (and the API key in it) is never returned, so nothing
      // secret reaches the log.
      if ("authorization".equals(streamName)) {
        JsonNode authData = root.path("data");
        String status = authData.path("status").asText("");
        // An unauthorized socket stays OPEN and simply honors no subscriptions — indistinguishable
        // from a healthy quiet one, which is how the June header-auth bug (#471) hid. Anything that
        // is not explicitly "authorized" warns, including a reply with no status at all: an
        // unrecognized shape must fail loud rather than be assumed successful.
        if ("authorized".equals(status)) {
          log.info(
              "fill-listener[{}] authorization reply status={} action={}",
              tenant,
              status,
              authData.path("action").asText(""));
        } else {
          log.warn(
              "fill-listener[{}] authorization reply NOT authorized status={} action={} message={}"
                  + " — the socket will stay open and deliver nothing",
              tenant,
              status,
              authData.path("action").asText(""),
              authData.path("message").asText(""));
        }
        return;
      }
      // #715: the subscription ack. Alpaca answers a successful `listen` with
      // {"stream":"listening","data":{"streams":["trade_updates"]}}. This frame used to hit the
      // catch-all `return` below, which meant there was NO positive evidence that a subscription
      // had ever succeeded — an authorized-but-unsubscribed socket looked exactly like a healthy
      // quiet one. That is the same blind spot #693 closed for the auth frame, one step downstream,
      // and it is why a full RTH session delivered zero trade_updates without anything going red.
      if ("listening".equals(streamName)) {
        JsonNode streams = root.path("data").path("streams");
        boolean subscribed = false;
        for (JsonNode s : streams) {
          if ("trade_updates".equals(s.asText())) {
            subscribed = true;
            break;
          }
        }
        // Alpaca echoes the EFFECTIVE subscription: "if any of the requested streams are not
        // available, they will not appear in the streams list in the acknowledgement". So an ack
        // that omits trade_updates is a FAILED subscription in a success-shaped frame, and must not
        // be logged at the same level as the real thing. A missing data/streams node iterates zero
        // times and lands here too — an unrecognized shape fails loud rather than reading as
        // success, matching the authorization branch above.
        //
        // This is NOT the pre-auth-race signature: Alpaca answers a `listen` sent before
        // authorization on the AUTHORIZATION stream (status=unauthorized, action=listen), which the
        // branch above already logs. Two distinct failures, deliberately kept distinguishable.
        if (subscribed) {
          metrics.recordSubscriptionConfirmed();
          log.info(
              "fill-listener[{}] subscription confirmed data={} — the socket IS subscribed",
              tenant,
              root.path("data"));
        } else {
          log.warn(
              "fill-listener[{}] subscription ack does NOT name trade_updates data={} — the stream"
                  + " is not available/entitled, so the socket is authorized but will deliver"
                  + " nothing. This is NOT the pre-auth race (that reports on the authorization"
                  + " stream as action=listen).",
              tenant,
              root.path("data"));
        }
        return;
      }
      if (!"trade_updates".equals(streamName)) {
        // Never discard an unmodelled frame silently again — a server-side error reply arriving
        // here is exactly the evidence a mute socket needs, and it used to be dropped without
        // trace. Damped to one WARN per distinct stream value per connection; see
        // unhandledStreamsWarned for why it is not per-process and not DEBUG.
        if (unhandledStreamsWarned.size() < MAX_UNHANDLED_STREAMS_WARNED
            && unhandledStreamsWarned.add(streamName)) {
          log.warn(
              "fill-listener[{}] unhandled stream={} — not modelled by this listener; logged once"
                  + " per distinct stream per connection",
              tenant,
              streamName);
        }
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

    /**
     * #693: Alpaca delivers trade_updates as <b>binary</b> frames carrying JSON. Before this
     * accumulator existed the JDK's default {@code onBinary} silently {@code request(1)}'d and
     * discarded every frame, so no fill ever reached the dispatcher over the WebSocket and the 30s
     * poller behind a 60s grace window discovered all of them (observe lag: BUY p50 69.2s, SELL p50
     * 30.2s).
     *
     * <p>This accumulates <b>bytes</b>, deliberately — not chars like {@link #partialFrame}. A
     * UTF-8 multi-byte sequence can straddle a fragment boundary, so decoding each fragment
     * independently corrupts the character at the seam. Decode happens exactly once, on {@code
     * last}. Kept separate from {@link #partialFrame} because RFC 6455 forbids interleaving the
     * fragments of two data messages, so the two accumulators can never be live simultaneously.
     */
    private final ByteArrayOutputStream partialBinaryFrame = new ByteArrayOutputStream();

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
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      if (partialBinaryFrame.size() + data.remaining() > MAX_FRAME_BYTES) {
        log.warn(
            "fill-listener[{}] binary frame exceeds {} bytes; aborting socket to recover via"
                + " reconnect",
            owner.tenant,
            MAX_FRAME_BYTES);
        partialBinaryFrame.reset();
        webSocket.abort();
        closed.countDown();
        return null;
      }
      byte[] chunk = new byte[data.remaining()];
      data.get(chunk);
      partialBinaryFrame.writeBytes(chunk);
      if (last) {
        String frame = partialBinaryFrame.toString(StandardCharsets.UTF_8);
        partialBinaryFrame.reset();
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
