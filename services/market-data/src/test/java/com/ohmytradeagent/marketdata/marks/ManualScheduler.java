package com.ohmytradeagent.marketdata.marks;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A {@link ScheduledExecutorService} stand-in that never runs anything on its own: every fixed-rate
 * task is captured so a test can fire it deterministically, and every returned future is a Mockito
 * mock so cancellation can be verified.
 */
public final class ManualScheduler {
  public final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
  public final List<Runnable> tasks = new ArrayList<>();
  public final List<ScheduledFuture<?>> futures = new ArrayList<>();
  public final List<Long> periodsMs = new ArrayList<>();

  public ManualScheduler() {
    when(executor.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any()))
        .thenAnswer(
            inv -> {
              tasks.add(inv.getArgument(0));
              long period = inv.getArgument(2);
              TimeUnit unit = inv.getArgument(3);
              periodsMs.add(unit.toMillis(period));
              ScheduledFuture<?> f = mock(ScheduledFuture.class);
              futures.add(f);
              return f;
            });
  }

  /** Runs every captured task once, in registration order. */
  public void runAll() {
    for (Runnable r : List.copyOf(tasks)) {
      r.run();
    }
  }
}
