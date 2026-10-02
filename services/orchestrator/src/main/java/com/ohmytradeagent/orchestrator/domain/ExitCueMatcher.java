package com.ohmytradeagent.orchestrator.domain;

import java.util.regex.Pattern;

/**
 * Detects whether a copytrade STC tail says anything about exiting at all. Gates the close-intent
 * classifier's FULL promotion: on 2026-10-01 the classifier read the bare exclamation "BANG" as a
 * full close (0.71) and flattened 16 INTC contracts the author called a partial. A tail with no
 * exit word carries no full-close information, so the classifier must not be allowed to act on it.
 *
 * <p>Deliberately broad — this is not a sizer, only a "the author talked about getting out" check;
 * the classifier still decides full-vs-partial. Whole-word matches so "haircut" / "without" do not
 * trip "cut" / "out". The vocabulary covers every classifier promotion observed on prod_real
 * (2026-08 → 2026-10): "cut the rest", "not letting it go red", "gotta dump these".
 *
 * <p>Pure and deterministic — safe to call from workflow code. Null / blank tail → false.
 */
public final class ExitCueMatcher {

  private static final Pattern EXIT_CUE =
      Pattern.compile(
          "\\b(out|cut|cutting|dump|dumped|dumping|sell|selling|sold|exit|exited|exiting"
              + "|close|closed|closing|flat|rest|remaining|runner|runners|last|everything"
              + "|red|stop|stopped|bail|bailed|bailing)\\b",
          Pattern.CASE_INSENSITIVE);

  private ExitCueMatcher() {}

  /** True when {@code tail} contains at least one whole-word exit cue. */
  public static boolean mentionsExit(String tail) {
    return tail != null && EXIT_CUE.matcher(tail).find();
  }
}
