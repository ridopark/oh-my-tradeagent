package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.google.protobuf.ByteString;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.temporal.LenientDataConverter;
import io.temporal.api.common.v1.Payload;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import java.io.InputStream;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Issue #772: prove that a recorded history whose {@code StrategyConfig} payload carries a field
 * ABSENT from the current schema replays clean under the converter the workers actually run — i.e.
 * that removing a schema field can no longer wedge an in-flight workflow.
 *
 * <p>The fixture is {@code copytrade-signal-pre-111-legacy-history.json} with two keys absent from
 * today's {@code strategy-config.json} injected into the recorded {@code GetStrategyConfig}
 * activity RESULT payload (event 12) — the shape a since-removed field takes in an old history
 * (#649's replay guard failed on exactly this: {@code Unrecognized field}). Activity results,
 * unlike activity inputs, ARE deserialized by the SDK when the workflow code consumes them on
 * replay, so this drives the precise mechanism that wedged #649. The fixture generator ({@code
 * scripts/gen-removed-config-field-replay-fixture.py}) asserts the injected keys collide with NO
 * current schema property, so the fixture stays discriminating as the schema evolves.
 *
 * <p>Two directions, so the proof cannot rot:
 *
 * <ul>
 *   <li>Under {@link LenientDataConverter} — the converter every worker service wires into its
 *       {@code WorkflowClient} (#772) — the replay completes.
 *   <li>Under the SDK's strict default converter the same fixture still fails with {@link
 *       UnrecognizedPropertyException}. If this second test ever fails, either the fixture lost its
 *       unknown keys or the SDK default went lenient — in both cases the first test would be
 *       proving nothing, which is exactly when we want a red build.
 * </ul>
 *
 * <p>The leniency must be DEPLOYED to every worker before any schema field is actually removed
 * (#338, #649 stay blocked until then) — the pod doing the replay is the one that needs it.
 */
class StrategyConfigLenientReplayTest {

  private static final String FIXTURE_RESOURCE =
      "temporal/replay/copytrade-signal-removed-config-field-history.json";

  @Test
  void configWithSinceRemovedFieldReplaysCleanUnderTheWiredLenientConverter() throws Exception {
    assertThat(getClass().getClassLoader().getResource(FIXTURE_RESOURCE))
        .as("Missing fixture resource %s", FIXTURE_RESOURCE)
        .isNotNull();

    TestEnvironmentOptions options =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder()
                    .setDataConverter(LenientDataConverter.instance())
                    .build())
            .build();
    try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance(options)) {
      WorkflowReplayer.replayWorkflowExecutionFromResource(
          FIXTURE_RESOURCE, env, CopytradeSignalWorkflowImpl.class);
    }
  }

  /**
   * Pins the fixture's discriminating property at the converter layer, on the exact recorded
   * payload bytes the replay consumes: the SDK's strict default converter must reject them with
   * {@code UnrecognizedPropertyException}, and {@link LenientDataConverter} must accept them. If
   * the strict half ever goes green, either the fixture lost its unknown keys or the SDK default
   * went lenient — in both cases the replay test above would be proving nothing, which is exactly
   * when we want a red build. (Asserted here rather than through {@link WorkflowReplayer} because
   * the replayer buries the deserialization failure in a message string, severing the cause chain.)
   */
  @Test
  void fixtureStaysDiscriminating_theStrictDefaultConverterStillRejectsItsConfigPayload()
      throws Exception {
    Payload recorded = recordedGetStrategyConfigResult();

    assertThatThrownBy(
            () ->
                DefaultDataConverter.newDefaultInstance()
                    .fromPayload(recorded, StrategyConfig.class, StrategyConfig.class))
        .hasRootCauseInstanceOf(UnrecognizedPropertyException.class)
        .rootCause()
        .hasMessageContaining("watchlist_expiry_rule");

    StrategyConfig lenient =
        LenientDataConverter.instance()
            .fromPayload(recorded, StrategyConfig.class, StrategyConfig.class);
    assertThat(lenient.getBrokerTarget().value()).isEqualTo("alpaca-paper");
  }

  /** Extracts event 12's ActivityTaskCompleted result payload — the recorded StrategyConfig. */
  private Payload recordedGetStrategyConfigResult() throws Exception {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(FIXTURE_RESOURCE)) {
      JsonNode history = new ObjectMapper().readTree(in);
      JsonNode payload =
          history
              .get("events")
              .get(12)
              .get("activityTaskCompletedEventAttributes")
              .get("result")
              .get("payloads")
              .get(0);
      Payload.Builder b = Payload.newBuilder();
      payload
          .get("metadata")
          .fields()
          .forEachRemaining(
              e ->
                  b.putMetadata(
                      e.getKey(),
                      ByteString.copyFrom(Base64.getDecoder().decode(e.getValue().asText()))));
      b.setData(ByteString.copyFrom(Base64.getDecoder().decode(payload.get("data").asText())));
      return b.build();
    }
  }
}
