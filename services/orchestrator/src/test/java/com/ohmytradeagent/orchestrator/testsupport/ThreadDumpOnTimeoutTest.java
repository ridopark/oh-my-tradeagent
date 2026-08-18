package com.ohmytradeagent.orchestrator.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The dump-on-timeout hook is only worth having if it actually fires, and only worth trusting if it
 * stays silent otherwise. Both are asserted here WITHOUT needing a real 60s hang, so this test
 * costs milliseconds and can run on every build.
 */
class ThreadDumpOnTimeoutTest {

  @Test
  void dumpsAllThreadsWhenATestFailsWithATimeout() throws Exception {
    // A thread parked on a lock somebody else owns is the shape we are hunting in #723: a Temporal
    // worker blocked while an Update is in flight. Build that shape for real so the assertion is
    // about observed output, not about the code path merely being entered.
    Object lock = new Object();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread blocker =
        new Thread(
            () -> {
              synchronized (lock) {
                holding.countDown();
                try {
                  release.await();
                } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
                }
              }
            },
            "dump-probe-owner");
    blocker.setDaemon(true);
    blocker.start();
    holding.await();

    String out;
    try {
      out = captureStdout(() -> new ThreadDumpOnTimeout().testFailed(ctx("someTest()"), timeout()));
    } finally {
      release.countDown();
      blocker.join(5_000L);
    }

    assertThat(out).contains("THREAD DUMP").contains("someTest()");
    assertThat(out)
        .as("the dump must name the threads, which is the whole point")
        .contains("dump-probe-owner");
    assertThat(out).contains("END THREAD DUMP");
  }

  @Test
  void staysSilentOnAnOrdinaryFailure() {
    // A dump on every assertion failure would bury the actual message under hundreds of lines and
    // make people stop reading CI output — worse than not having it.
    String out =
        captureStdout(
            () ->
                new ThreadDumpOnTimeout()
                    .testFailed(ctx("someTest()"), new AssertionError("expected true")));

    assertThat(out).isEmpty();
  }

  @Test
  void detectsATimeoutNestedInsideAWrapper() {
    // JUnit does not guarantee the TimeoutException arrives unwrapped; a cause-chain walk is why
    // this works. Checking only the top-level type would silently no-op on a wrapped hang, which is
    // indistinguishable from the hook not being installed at all.
    String out =
        captureStdout(
            () ->
                new ThreadDumpOnTimeout()
                    .testFailed(ctx("someTest()"), new ExecutionException("wrapped", timeout())));

    assertThat(out).contains("THREAD DUMP");
  }

  private static TimeoutException timeout() {
    return new TimeoutException("someTest() timed out after 60 seconds");
  }

  private static ExtensionContext ctx(String displayName) {
    ExtensionContext context = mock(ExtensionContext.class);
    when(context.getDisplayName()).thenReturn(displayName);
    return context;
  }

  private static String captureStdout(Runnable body) {
    PrintStream original = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
    try {
      body.run();
    } finally {
      System.setOut(original);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }
}
