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
}
