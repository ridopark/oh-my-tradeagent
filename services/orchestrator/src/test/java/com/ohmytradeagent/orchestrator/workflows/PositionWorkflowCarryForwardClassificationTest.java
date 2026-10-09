package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Issue #752 (PLAN-2026-10-08 Phase 1): every {@link PositionWorkflowImpl} instance field must be
 * explicitly classified for the continue-as-new roll, so adding a field — or a version gate —
 * without deciding what happens to it at the roll fails the build. The barrier javadoc's old claim
 * ("every field not carried is proven zero by a conjunct") had already drifted once ({@code
 * lastTickObservedAt}); this test is now the source of truth for that pairing.
 */
class PositionWorkflowCarryForwardClassificationTest {

  private static final String CLASSIFY_HINT =
      "classify this field. If it is a version gate that changes WHEN a stop fires, a carried run"
          + " must keep the ORIGINAL run's version. Carry it and take min(carried, resolved) in"
          + " run(). See PLAN-2026-10-08 §3 G1.";

  /** Copied into the next run's input by buildCarryForwardInput and hydrated in @WorkflowInit. */
  private static final Set<String> CARRIED =
      Set.of(
          "remainingQty",
          "expectedQty", // carried_expected_qty, re-read in run()
          "firstFillReceived", // hydrated true: the carried marker implies the first fill happened
          "positionConfirmed", // hydrated true; the barrier also requires it
          "processedSignalIds",
          "entryBrokerOrderId",
          "entryBookedQty",
          "entryFillPrice",
          "trailArmedByOperator",
          "flattenRetrySessions",
          "exitBookedByOrder",
          "partialPlaceRetrySessions",
          "partialPlaceRetryAttempts",
          "trailingArmed",
          "peakPremium",
          "givebackPct",
          "ticksReceived",
          "lastTickPremium",
          "lastTickAt",
          "entryAt",
          "partialExited");

  /**
   * Proven at its zero value by a rollBarrierHolds() conjunct — directly, or (noted inline) because
   * the field is only non-zero while a named conjunct already blocks the roll.
   */
  private static final Set<String> BARRIER_ZERO =
      Set.of(
          "exitInFlight",
          // set only alongside exitInFlight=true, cleared by releaseExitInFlightLatches()
          "currentInFlightBrokerOrderId",
          "currentInFlightSignalId",
          "currentInFlightIntentKey",
          "lastFillEvent",
          "flattenAwaitingLateFill",
          "retryFlattenArmed",
          "partialPlaceRetryPending",
          "partialPlaceRetryArmed",
          "pendingExits",
          "pendingArms",
          "pendingTicks",
          "pendingRiskBreaches",
          "pendingForceCloses",
          "pendingPartialCloses",
          "pendingSupersedes",
          "chandelierFireRequested",
          // written only with chandelierFireRequested; firing sets closeReason (never reset)
          "fireTriggerTick",
          "fireThreshold",
          "exitStopFireRequested",
          "exitTimeStopFired",
          "exitFeedStaleFired",
          "eodFired",
          "expiryFired",
          "expiryLeadFired",
          "closeReason",
          // written only by flatten bookkeeping; every flatten path sets closeReason first
          "flattenBookedKey",
          "flattenBookedQty");

  /** Watchlist-exit state; the barrier's input.getTpRatio() == null conjunct excludes these. */
  private static final Set<String> WATCHLIST_ONLY =
      Set.of(
          "exitArmed",
          "exitStopLevel",
          "exitTargetLevel",
          "exitTpPartialFraction",
          "exitTrailGiveback",
          "exitTargetFired",
          "exitSubThresholdStreak",
          "exitBidDegradedAudited",
          "exitTickSeen",
          "exitEntryBasis",
          "exitBidMfe",
          "exitBidMae",
          "exitFirstFillAt",
          "lastBid");

  /** Rebuilt by every run from its input / constructor; nothing to carry. */
  private static final Set<String> DERIVED_AT_RUN =
      Set.of(
          "input",
          "exec",
          "brokerTarget",
          "audit",
          "calendar",
          "marketData",
          "optionQuote",
          "carriedRun",
          "initGatesResolved");

  /**
   * Reset to null at the roll and accepted. lastTickObservedAt is display-only today ("nothing
   * reads it yet"); the PR that gives it a reader (e.g. a feed-staleness backstop) must carry it or
   * prove it zero, because a null right after a roll reads as "never heard a tick".
   */
  private static final Set<String> RESETS_AT_ROLL_ACCEPTED = Set.of("lastTickObservedAt");

  /**
   * Top-of-run getVersion fields. A carried run has an empty history, so each resolves to MAX — a
   * run that started below a gate silently switches to the gated behaviour at the roll (G1).
   * Accepted today: every running position post-dates breakeven-floor and trail-on-bid (verified
   * 2026-10-08), and both of those only tighten a stop.
   */
  private static final Set<String> VERSION_RESOLVES_MAX_ON_CARRIED_RUN =
      Set.of(
          "entryGrowthVersion", "fillClearVersion", "breakevenFloorVersion", "trailOnBidVersion");

  /**
   * Every VERSION_* gate constant. In-method getVersion calls resolve to MAX on a carried run just
   * like the top-of-run fields, so a new gate must be acknowledged here — reading it into a local
   * instead of a field does not bypass the G1 decision.
   */
  private static final Set<String> VERSION_GATES_ACKNOWLEDGED =
      Set.of(
          "VERSION_CHANDELIER",
          "VERSION_RISK_BREACH",
          "VERSION_FORCE_CLOSE",
          "VERSION_WATCHLIST_EXIT",
          "VERSION_TIMESTOP_PRETARGET_ONLY",
          "VERSION_EXIT_FILL_TIMEOUT",
          "VERSION_DEFER_POSITION_ENTERED",
          "VERSION_MIN_PARTIAL_QTY_SKIP",
          "VERSION_TTL_FROM_INPUT",
          "VERSION_EXIT_RETRY_ON_TIMEOUT",
          "VERSION_EXIT_RETRY_SOURCE_ORDER",
          "VERSION_EXIT_FILLED_OPTION_SYMBOL",
          "VERSION_EOD_FLATTEN_OPT_IN",
          "VERSION_EXIT_RETRY_LATE_FILL_RECONCILE",
          "VERSION_EXIT_CANCEL_TERMINAL_RECONCILE",
          "VERSION_FLATTEN_CANCEL_TERMINAL_RECONCILE",
          "VERSION_EXIT_PLACE_FAILURE_GUARD",
          "VERSION_FLATTEN_FILL_AWAIT",
          "VERSION_FLATTEN_BOUNDED_LIMIT",
          "VERSION_EXPIRY_LEAD_FLATTEN",
          "VERSION_EXPIRE_WORTHLESS_NO_TIMER",
          "VERSION_EXIT_STEPPED_REPRICE",
          "VERSION_EXPIRE_WORTHLESS",
          "VERSION_EXPIRE_WORTHLESS_SCHEDULED",
          "VERSION_FLATTEN_RETRY_NEXT_SESSION",
          "VERSION_BTO_CORRECTION_SUPERSEDE",
          "VERSION_PARTIAL_PLACE_RETRY_NEXT_SESSION",
          "VERSION_EXIT_CUMULATIVE_LEDGER",
          "VERSION_EXIT_PARTIAL_AWAIT_LOOP",
          "VERSION_ENTRY_FILL_NOT_AN_EXIT",
          "VERSION_ENTRY_FILL_GROWS_LOT",
          "VERSION_FILL_COMPARE_AND_CLEAR",
          "VERSION_CHANDELIER_BREAKEVEN_FLOOR",
          "VERSION_CHANDELIER_TRAIL_ON_BID",
          "VERSION_BUFFERED_OPERATOR_AUDIT",
          "VERSION_RISK_BREACH_EXEMPT_LONG_DATED");

  private static final Map<String, Set<String>> CLASSES = new LinkedHashMap<>();

  static {
    CLASSES.put("CARRIED", CARRIED);
    CLASSES.put("BARRIER_ZERO", BARRIER_ZERO);
    CLASSES.put("WATCHLIST_ONLY", WATCHLIST_ONLY);
    CLASSES.put("DERIVED_AT_RUN", DERIVED_AT_RUN);
    CLASSES.put("RESETS_AT_ROLL_ACCEPTED", RESETS_AT_ROLL_ACCEPTED);
    CLASSES.put("VERSION_RESOLVES_MAX_ON_CARRIED_RUN", VERSION_RESOLVES_MAX_ON_CARRIED_RUN);
  }

  private static List<Field> instanceFields() {
    return Arrays.stream(PositionWorkflowImpl.class.getDeclaredFields())
        .filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.isSynthetic())
        .toList();
  }

  @Test
  void everyFieldIsClassifiedExactlyOnce() {
    List<String> violations = new ArrayList<>();
    for (Field f : instanceFields()) {
      List<String> in =
          CLASSES.entrySet().stream()
              .filter(e -> e.getValue().contains(f.getName()))
              .map(Map.Entry::getKey)
              .toList();
      if (in.size() != 1) {
        violations.add(f.getName() + " in " + in);
      }
    }
    assertThat(violations).as(CLASSIFY_HINT).isEmpty();
  }

  @Test
  void versionFieldsAreExactlyTheIntVersionFields() {
    Set<String> intVersionFields =
        instanceFields().stream()
            .filter(f -> f.getType() == int.class && f.getName().endsWith("Version"))
            .map(Field::getName)
            .collect(Collectors.toCollection(TreeSet::new));
    assertThat(intVersionFields)
        .as(CLASSIFY_HINT)
        .containsExactlyInAnyOrderElementsOf(VERSION_RESOLVES_MAX_ON_CARRIED_RUN);
  }

  @Test
  void versionGateConstantsAreAcknowledged() {
    Set<String> gates =
        Arrays.stream(PositionWorkflowImpl.class.getDeclaredFields())
            .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
            .map(Field::getName)
            .filter(n -> n.startsWith("VERSION_"))
            .collect(Collectors.toCollection(TreeSet::new));
    assertThat(gates)
        .as("a new getVersion gate resolves to MAX on a carried run — " + CLASSIFY_HINT)
        .containsExactlyInAnyOrderElementsOf(VERSION_GATES_ACKNOWLEDGED);
  }

  @Test
  void classifiedNamesAllExist() {
    Set<String> declared =
        instanceFields().stream().map(Field::getName).collect(Collectors.toSet());
    Set<String> stale = new TreeSet<>();
    CLASSES
        .values()
        .forEach(s -> s.stream().filter(n -> !declared.contains(n)).forEach(stale::add));
    assertThat(stale).as("stale names after a field rename/removal").isEmpty();
  }
}
