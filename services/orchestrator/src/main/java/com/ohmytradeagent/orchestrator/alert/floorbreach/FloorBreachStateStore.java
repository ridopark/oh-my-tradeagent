package com.ohmytradeagent.orchestrator.alert.floorbreach;

import com.ohmytradeagent.orchestrator.alert.floorbreach.FloorBreachEvaluator.Evaluation;
import com.ohmytradeagent.orchestrator.alert.floorbreach.FloorBreachEvaluator.Status;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Issue #779: per-position hysteresis state for the floor-breach alerter. Decides, per observed
 * tick, whether an alert fires — the whole point is that a multi-hour bleed pages a handful of
 * times, not once per poll, and quote flicker around the floor line pages exactly once.
 *
 * <p><b>Hysteresis rules</b> (all deterministic, all unit-tested):
 *
 * <ul>
 *   <li><b>Confirmation:</b> the first alert requires the breach observed on 2 CONSECUTIVE ticks —
 *       a single aberrant unfiltered snapshot must not page. At a 60s poll this delays a real page
 *       by one minute against multi-hour bleeds.
 *   <li><b>Re-alert:</b> only when the 10%-bucketed loss step deepens by ≥10 (each further 10%
 *       lost), or when still breached and the re-alert interval (default 4h) has elapsed.
 *   <li><b>Re-arm:</b> state resets (so a future breach pages again) only when the bid recovers to
 *       ≥ 1.10 x the floor line — flicker AROUND the line can neither spam nor re-arm.
 *   <li><b>UNKNOWN ticks are inert:</b> they neither confirm, re-arm, nor alert.
 * </ul>
 *
 * <p><b>Why in-memory (+ audit backstop), not a table.</b> The state is per-open-position, small
 * (&lt;100 entries estate-wide), and derivable; a durable store would be a new table for a
 * notification feature (KISS). The restart / rolling-deploy backstop is the {@code audit_log}
 * itself: before the FIRST alert of this process lifetime for a workflow, one read-only SELECT
 * fetches the latest {@code FloorBreachAlerted} row for that workflow id; if that row's step is
 * already ≥ the candidate step AND younger than the re-alert interval, the alert is suppressed and
 * the in-memory state rehydrated from it. A restart therefore costs at most one early re-alert
 * after the interval, never a spam burst; a two-pod roll can in the worst case race the
 * SELECT/INSERT window into ONE duplicate embed — harmless for an alert-only feature.
 */
@Component
public class FloorBreachStateStore {

  private static final Logger log = LoggerFactory.getLogger(FloorBreachStateStore.class);

  /** Consecutive breach ticks required before the first alert. */
  static final int CONFIRMATION_TICKS = 2;

  /** The step deepening (percentage points of loss) that triggers a re-alert. */
  static final int REALERT_STEP = 10;

  /** Latest prior {@code FloorBreachAlerted} for a workflow, from the audit backstop. */
  public record PriorAlert(int step, Instant at) {}

  /** Read-only backstop lookup. Best-effort: errors → {@code null} (treated as no prior row). */
  public interface PriorAlertLookup {
    PriorAlert find(String workflowId);
  }

  /** The per-tick decision: whether to alert, and at which step. */
  public record Decision(boolean alert, int step) {
    static final Decision SILENT = new Decision(false, 0);
  }

  private static final class State {
    int consecutiveBreachTicks;
    boolean alerted;
    int deepestAlertedStep;
    Instant lastAlertAt;
    boolean backstopChecked;
  }

  private final Map<String, State> states = new ConcurrentHashMap<>();
  private final Duration realertInterval;
  private final PriorAlertLookup priorAlertLookup;

  @Autowired
  public FloorBreachStateStore(
      @Autowired(required = false) DSLContext dsl,
      @Value("${alert.floor-breach.realert-hours:4}") long realertHours) {
    this(dbLookup(dsl), Duration.ofHours(realertHours));
  }

  /** Explicit-dependency constructor (also the test seam). */
  public FloorBreachStateStore(PriorAlertLookup priorAlertLookup, Duration realertInterval) {
    this.priorAlertLookup = priorAlertLookup;
    this.realertInterval = realertInterval;
  }

  /**
   * Feeds one observed evaluation for {@code workflowId} and returns whether an alert fires.
   * Deterministic given the tick sequence; never throws.
   */
  public Decision onTick(String workflowId, Evaluation eval, Instant now) {
    if (eval == null || eval.status() == Status.UNKNOWN) {
      return Decision.SILENT;
    }
    if (eval.status() == Status.OK) {
      if (eval.recoveredAboveBand()) {
        // Full re-arm: a future breach pages again from scratch.
        states.remove(workflowId);
      } else {
        State s = states.get(workflowId);
        if (s != null) {
          // OK inside the band: the confirmation counter resets (ticks must be consecutive) but
          // the alerted latch survives, so flicker around the line cannot page twice.
          s.consecutiveBreachTicks = 0;
        }
      }
      return Decision.SILENT;
    }

    // BREACH
    int step = eval.step();
    State s = states.computeIfAbsent(workflowId, k -> new State());
    s.consecutiveBreachTicks++;
    if (!s.alerted) {
      if (s.consecutiveBreachTicks < CONFIRMATION_TICKS) {
        return Decision.SILENT;
      }
      if (!s.backstopChecked) {
        s.backstopChecked = true;
        PriorAlert prior = safeLookup(workflowId);
        if (prior != null
            && prior.step() >= step
            && Duration.between(prior.at(), now).compareTo(realertInterval) < 0) {
          // A pre-restart alert already covers this depth and is recent: rehydrate, stay silent.
          s.alerted = true;
          s.deepestAlertedStep = prior.step();
          s.lastAlertAt = prior.at();
          return Decision.SILENT;
        }
      }
      s.alerted = true;
      s.deepestAlertedStep = step;
      s.lastAlertAt = now;
      return new Decision(true, step);
    }
    if (step >= s.deepestAlertedStep + REALERT_STEP) {
      s.deepestAlertedStep = step;
      s.lastAlertAt = now;
      return new Decision(true, step);
    }
    if (Duration.between(s.lastAlertAt, now).compareTo(realertInterval) >= 0) {
      s.lastAlertAt = now;
      return new Decision(true, step);
    }
    return Decision.SILENT;
  }

  /** Evicts state for workflows no longer running, so a closed position cannot linger. */
  public void retainOnly(Set<String> runningWorkflowIds) {
    states.keySet().retainAll(runningWorkflowIds);
  }

  private PriorAlert safeLookup(String workflowId) {
    if (priorAlertLookup == null) {
      return null;
    }
    try {
      return priorAlertLookup.find(workflowId);
    } catch (RuntimeException e) {
      // Best-effort: a failed backstop read costs at most one duplicate embed after a restart.
      log.warn("floor-breach backstop lookup failed wf={}: {}", workflowId, e.getMessage());
      return null;
    }
  }

  /**
   * The production backstop: latest {@code FloorBreachAlerted} audit row for a workflow id. A
   * {@code null} DSL (test/boot envs without a DataSource) degrades to a no-prior-row lookup.
   */
  private static PriorAlertLookup dbLookup(DSLContext dsl) {
    if (dsl == null) {
      return workflowId -> null;
    }
    return workflowId -> {
      try {
        Record row =
            dsl.fetchOne(
                "SELECT subject->>'step' AS step, occurred_at FROM audit_log "
                    + "WHERE kind = 'FloorBreachAlerted' AND workflow_id = ? "
                    + "ORDER BY id DESC LIMIT 1",
                workflowId);
        if (row == null) {
          return null;
        }
        String step = row.get("step", String.class);
        Timestamp at = row.get("occurred_at", Timestamp.class);
        if (step == null || at == null) {
          return null;
        }
        return new PriorAlert(Integer.parseInt(step), at.toInstant());
      } catch (RuntimeException e) {
        log.warn("floor-breach backstop query failed wf={}: {}", workflowId, e.getMessage());
        return null;
      }
    };
  }
}
