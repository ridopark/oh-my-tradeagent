package com.ohmytradeagent.orchestrator.bootstrap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
import io.temporal.client.schedules.ScheduleClient;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase A: {@link TenantReconcileLoop} ensures kill-switch + recon schedule for every enumerated
 * {@code (tenant, strategy)} via the SAME idempotent bootstrapper logic the boot path uses, picks
 * up a newly-inserted pair on the next tick WITHOUT a restart, and is a no-op on already-seen
 * pairs.
 */
class TenantReconcileLoopTest {

  private static final TenantStrategy A = new TenantStrategy("acme", "strat-a");
  private static final TenantStrategy B = new TenantStrategy("beta", "strat-b");

  private StrategyRegistry registry;
  private KillSwitchBootstrapper killSwitch;
  private ReconciliationScheduleBootstrapper recon;
  private ScheduleClient scheduleClient;
  private TenantReconcileLoop loop;

  @BeforeEach
  void setUp() {
    registry = mock(StrategyRegistry.class);
    killSwitch = mock(KillSwitchBootstrapper.class);
    recon = mock(ReconciliationScheduleBootstrapper.class);
    scheduleClient = mock(ScheduleClient.class);
    when(recon.newScheduleClient()).thenReturn(scheduleClient);
    // Default: both ensures succeed, so a pair is marked seen and not re-ensured. Tests that
    // exercise the failure/retry path override these for specific args.
    when(killSwitch.ensureForTenantStrategy(any(), any())).thenReturn(true);
    when(recon.ensureForTenantStrategy(any(), any(), any())).thenReturn(true);
    loop = new TenantReconcileLoop(registry, killSwitch, recon);
  }

  /**
   * Running set {A} already ensured, desired {A,B} → the loop ensures ONLY B's kill-switch + recon
   * schedule, and a second tick (desired unchanged) is a complete no-op (seen-set idempotency).
   */
  @Test
  void ensuresOnlyNewPairThenIsNoOpOnSecondTick() {
    // Tick 1: registry knows only A → A becomes the running/seen set.
    when(registry.list()).thenReturn(List.of(A));
    loop.reconcileTick();
    verify(killSwitch).ensureForTenantStrategy("acme", "strat-a");
    verify(recon).ensureForTenantStrategy(scheduleClient, "acme", "strat-a");

    // Tick 2: a new pair B appears (simulating a UI-written strategy_config row).
    when(registry.list()).thenReturn(List.of(A, B));
    loop.reconcileTick();
    verify(killSwitch).ensureForTenantStrategy("beta", "strat-b");
    verify(recon).ensureForTenantStrategy(scheduleClient, "beta", "strat-b");
    // A must NOT be re-ensured — it was already seen on tick 1.
    verify(killSwitch, times(1)).ensureForTenantStrategy("acme", "strat-a");
    verify(recon, times(1)).ensureForTenantStrategy(scheduleClient, "acme", "strat-a");

    // Tick 3: desired unchanged → zero further ensure calls.
    loop.reconcileTick();
    verify(killSwitch, times(1)).ensureForTenantStrategy("acme", "strat-a");
    verify(killSwitch, times(1)).ensureForTenantStrategy("beta", "strat-b");
    verify(recon, times(1)).ensureForTenantStrategy(scheduleClient, "acme", "strat-a");
    verify(recon, times(1)).ensureForTenantStrategy(scheduleClient, "beta", "strat-b");
  }

  /** desired == running (all already seen) → zero state-mutating ensure calls on the next tick. */
  @Test
  void desiredEqualsRunningMakesNoMutatingCalls() {
    when(registry.list()).thenReturn(List.of(A, B));
    loop.reconcileTick(); // first tick seeds the seen-set with A and B
    verify(killSwitch).ensureForTenantStrategy("acme", "strat-a");
    verify(killSwitch).ensureForTenantStrategy("beta", "strat-b");

    // Second tick, identical desired set → nothing new to ensure.
    loop.reconcileTick();
    verify(killSwitch, times(1)).ensureForTenantStrategy("acme", "strat-a");
    verify(killSwitch, times(1)).ensureForTenantStrategy("beta", "strat-b");
    verify(recon, times(1)).ensureForTenantStrategy(scheduleClient, "acme", "strat-a");
    verify(recon, times(1)).ensureForTenantStrategy(scheduleClient, "beta", "strat-b");
  }

  /**
   * A newly-inserted row (simulating the Phase-I UI write) is reconciled on the next tick without a
   * restart: starting from empty, B appears and is ensured exactly once.
   */
  @Test
  void newlyInsertedPairIsReconciledOnNextTickWithoutRestart() {
    // Boot tick: empty registry → no ensure calls, no ScheduleClient even built.
    when(registry.list()).thenReturn(List.of());
    loop.reconcileTick();
    verify(recon, never()).newScheduleClient();
    verifyNoInteractions(killSwitch);

    // UI inserts a strategy_config row at runtime → enumerated on the next tick.
    when(registry.list()).thenReturn(List.of(B));
    loop.reconcileTick();
    verify(killSwitch, times(1)).ensureForTenantStrategy("beta", "strat-b");
    verify(recon, times(1)).ensureForTenantStrategy(scheduleClient, "beta", "strat-b");
  }

  /**
   * A transient ensure failure must NOT latch the pair as seen — it is retried on subsequent ticks
   * and marked seen only once it succeeds. Guards the restart-free safety guarantee: a pair whose
   * first ensure fails (e.g. Temporal briefly unreachable) must not be left without a kill-switch /
   * recon schedule until an orchestrator restart.
   */
  @Test
  void failedEnsureIsNotLatchedAndRetriesUntilSuccess() {
    when(registry.list()).thenReturn(List.of(A));

    // Tick 1: kill-switch ensure transiently fails → A must NOT be marked seen.
    when(killSwitch.ensureForTenantStrategy("acme", "strat-a")).thenReturn(false);
    loop.reconcileTick();
    verify(killSwitch, times(1)).ensureForTenantStrategy("acme", "strat-a");

    // Tick 2: still not seen → retried; now it succeeds → marked seen.
    when(killSwitch.ensureForTenantStrategy("acme", "strat-a")).thenReturn(true);
    loop.reconcileTick();
    verify(killSwitch, times(2)).ensureForTenantStrategy("acme", "strat-a");

    // Tick 3: now seen → no further ensure call.
    loop.reconcileTick();
    verify(killSwitch, times(2)).ensureForTenantStrategy("acme", "strat-a");
  }

  /** A failing registry.list() must not throw out of the tick (loop stays alive). */
  @Test
  void registryListFailureIsSwallowed() {
    when(registry.list()).thenThrow(new RuntimeException("db down"));
    loop.reconcileTick();
    verifyNoInteractions(killSwitch);
    verify(recon, never()).ensureForTenantStrategy(any(), any(), eq("strat-a"));
  }

  // --- startup pass -------------------------------------------------------

  /**
   * The startup pass ensures every pair the REGISTRY knows about, including ones absent from the
   * mounted tenants tree.
   *
   * <p>This is the assertion that lets the tree be retired. On the live cluster the mount held 4 of
   * 8 {@code (tenant, strategy)} pairs, with {@code prod-jinchul} and {@code paper_jinchiul} absent
   * from it entirely; before this pass existed those two had no kill switch until the first
   * scheduled tick, a full minute after boot.
   */
  @Test
  void startupPassEnsuresEveryRegistryPairIncludingThoseNotOnDisk() {
    TenantStrategy offDisk = new TenantStrategy("prod-jinchul", "copytrade-v1");
    when(registry.list()).thenReturn(List.of(A, B, offDisk));

    loop.run(null);

    verify(killSwitch).ensureForTenantStrategy("acme", "strat-a");
    verify(killSwitch).ensureForTenantStrategy("beta", "strat-b");
    verify(killSwitch).ensureForTenantStrategy("prod-jinchul", "copytrade-v1");
    verify(recon).ensureForTenantStrategy(scheduleClient, "prod-jinchul", "copytrade-v1");
  }

  /** Pairs ensured at startup are seen, so the first scheduled tick re-ensures nothing. */
  @Test
  void scheduledTickAfterStartupPassIsANoOp() {
    when(registry.list()).thenReturn(List.of(A, B));

    loop.run(null);
    loop.reconcileTick();

    verify(killSwitch, times(1)).ensureForTenantStrategy("acme", "strat-a");
    verify(killSwitch, times(1)).ensureForTenantStrategy("beta", "strat-b");
  }

  /** A startup pass that fails a pair must not latch it — the scheduled tick still retries. */
  @Test
  void startupPassFailureIsRetriedByTheScheduledTick() {
    when(registry.list()).thenReturn(List.of(A));
    when(killSwitch.ensureForTenantStrategy("acme", "strat-a")).thenReturn(false);

    loop.run(null);
    verify(killSwitch, times(1)).ensureForTenantStrategy("acme", "strat-a");

    when(killSwitch.ensureForTenantStrategy("acme", "strat-a")).thenReturn(true);
    loop.reconcileTick();
    verify(killSwitch, times(2)).ensureForTenantStrategy("acme", "strat-a");
  }

  /** A registry failure during the startup pass must not abort boot. */
  @Test
  void startupPassSurvivesARegistryFailure() {
    when(registry.list()).thenThrow(new RuntimeException("db not up yet"));

    loop.run(null); // must not throw — boot continues, the tick retries

    verifyNoInteractions(killSwitch);
  }

  /**
   * The startup pass and a scheduled tick must never run concurrently.
   *
   * <p>Before the startup pass existed, passes were serialized only because Spring's default {@code
   * fixedDelay} scheduler is single-threaded. {@link TenantReconcileLoop#run} executes on the MAIN
   * thread during boot, entirely outside that guarantee, so the two entry points could overlap and
   * double-ensure or interleave. The lock makes the second caller skip.
   *
   * <p>Deterministic, not timing-dependent: the startup pass is held inside {@code registry.list()}
   * until the scheduled tick has been attempted and returned.
   */
  @Test
  void scheduledTickSkipsWhileTheStartupPassIsInFlight() throws Exception {
    CountDownLatch startupInsidePass = new CountDownLatch(1);
    CountDownLatch tickAttempted = new CountDownLatch(1);

    when(registry.list())
        .thenAnswer(
            inv -> {
              startupInsidePass.countDown();
              // Hold the pass open until the competing tick has come and gone.
              if (!tickAttempted.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("competing tick never attempted");
              }
              return List.of(A);
            });

    Thread startup = new Thread(() -> loop.run(null), "startup-pass");
    startup.start();
    if (!startupInsidePass.await(5, TimeUnit.SECONDS)) {
      throw new AssertionError("startup pass never entered");
    }

    loop.reconcileTick(); // must return immediately without doing any work
    tickAttempted.countDown();
    startup.join(5_000);

    // registry.list() ran exactly ONCE: the tick skipped rather than starting a second pass.
    verify(registry, times(1)).list();
    verify(killSwitch, times(1)).ensureForTenantStrategy("acme", "strat-a");
  }
}
