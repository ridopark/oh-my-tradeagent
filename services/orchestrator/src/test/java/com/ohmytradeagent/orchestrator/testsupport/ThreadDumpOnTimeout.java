package com.ohmytradeagent.orchestrator.testsupport;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * Dumps every thread's stack when a test fails with a TIMEOUT, so a hang diagnoses itself.
 *
 * <p><b>Why this exists.</b> The orchestrator CI leg intermittently fails with {@code
 * PositionWorkflowImplTest.armTrail_* timed out after 60 seconds} (#723). That flake has never
 * reproduced locally — not at {@code forkCount=1}, not at {@code forkCount=2}, and not with the
 * suite pinned to two CPUs to imitate the runner — so it cannot be chased by re-running it here.
 * The failure message names the test and nothing else, which leaves "re-run and hope" as the only
 * available move. Every occurrence is therefore a wasted opportunity: the one moment the bug is
 * live is also the moment we record the least about it.
 *
 * <p>The open question is specific and a dump answers it directly: are the Temporal worker threads
 * blocked on the test service's time-skipping lock while a synchronous Update is in flight? Lock
 * ownership is printed ({@code dumpAllThreads(true, true)}), so a blocked-on/owned-by chain is
 * visible rather than inferred.
 *
 * <p><b>What it cannot show.</b> JUnit implements a per-test timeout by running the invocation on a
 * separate thread and abandoning it, so by the time {@link #testFailed} runs the TEST thread may
 * already be interrupted and partly unwound. The threads that matter here are the OTHER ones — the
 * Temporal workers and the in-process test service — and those are still parked exactly where they
 * were. Treat the test thread's own frames as unreliable and everything else as evidence.
 *
 * <p>Registered by ServiceLoader ({@code META-INF/services/...Extension}) with {@code
 * junit.jupiter.extensions.autodetection.enabled=true}, so it applies to every test in the module
 * without annotating anything. That matters because this flake has already surfaced in three
 * different classes; a per-class annotation would have missed the next one.
 *
 * <p>Costs nothing on a green run — {@link #testFailed} only fires on failure, and non-timeout
 * failures return immediately.
 */
public final class ThreadDumpOnTimeout implements TestWatcher {

  @Override
  public void testFailed(ExtensionContext context, Throwable cause) {
    if (!isTimeout(cause)) {
      return;
    }
    StringBuilder out = new StringBuilder();
    out.append("\n=== THREAD DUMP: ")
        .append(context.getDisplayName())
        .append(" failed with a timeout (see ThreadDumpOnTimeout, #723) ===\n")
        .append("cause: ")
        .append(cause)
        .append('\n');
    for (ThreadInfo info : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
      append(out, info);
    }
    out.append("=== END THREAD DUMP ===\n");
    // stdout so surefire captures it into the report the CI log already prints.
    System.out.println(out);
  }

  /** A JUnit per-test timeout surfaces as TimeoutException, sometimes wrapped. */
  private static boolean isTimeout(Throwable cause) {
    for (Throwable t = cause; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof TimeoutException) {
        return true;
      }
    }
    return false;
  }

  private static void append(StringBuilder out, ThreadInfo info) {
    out.append('"')
        .append(info.getThreadName())
        .append("\" #")
        .append(info.getThreadId())
        .append(' ')
        .append(info.getThreadState());
    if (info.getLockName() != null) {
      out.append(" on ").append(info.getLockName());
    }
    if (info.getLockOwnerName() != null) {
      // The load-bearing line: who is holding what this thread is waiting for.
      out.append(" owned by \"").append(info.getLockOwnerName()).append('"');
    }
    out.append('\n');
    StackTraceElement[] stack = info.getStackTrace();
    for (int i = 0; i < stack.length; i++) {
      out.append("\tat ").append(stack[i]).append('\n');
      for (MonitorInfo m : info.getLockedMonitors()) {
        if (m.getLockedStackDepth() == i) {
          out.append("\t- locked ").append(m).append('\n');
        }
      }
    }
    LockInfo[] synchronizers = info.getLockedSynchronizers();
    if (synchronizers.length > 0) {
      out.append("\tlocked synchronizers:\n");
      for (LockInfo l : synchronizers) {
        out.append("\t- ").append(l).append('\n');
      }
    }
    out.append('\n');
  }
}
