package com.ohmytradeagent.tdbff.positions;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.tdbff.positions.PositionsReader.TrailingStateView;
import io.temporal.common.converter.DefaultDataConverter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * {@link TrailingStateView} is a 3-field mirror of the orchestrator's 8-field {@code TrailingState}
 * query result — the armed trailing stop /live shows on each holdings row. These drive the SAME
 * converter the real query path uses ({@link DefaultDataConverter}), so they prove the real
 * mechanism rather than a hand-built mapper, exactly as {@code PositionStateViewDriftTest} does for
 * {@code positionState}.
 *
 * <p>The mirror is deliberately narrow: this reader wants armed / giveback / threshold and nothing
 * else, and {@code @JsonIgnoreProperties(ignoreUnknown = true)} is what lets the orchestrator keep
 * the other five fields (and add more) without dropping every position from the dashboard — the
 * outage {@code PositionStateViewDriftTest} was written for.
 */
class TrailingStateViewDriftTest {

  /** The full 8-field payload the orchestrator's {@code trailingState()} returns when armed. */
  private static final String ARMED_JSON =
      "{"
          + "\"armed\":true,"
          + "\"givebackPct\":0.35,"
          + "\"lastTickAt\":\"2026-08-18T18:31:00Z\","
          + "\"lastTickObservedAt\":\"2026-08-18T18:31:02Z\","
          + "\"lastTickPremium\":4.05,"
          + "\"peakPremium\":4.05,"
          + "\"thresholdPremium\":2.63,"
          + "\"ticksReceived\":412"
          + "}";

  /** What an UN-armed position answers: armed=false and every premium null. */
  private static final String UNARMED_JSON =
      "{"
          + "\"armed\":false,"
          + "\"givebackPct\":null,"
          + "\"lastTickAt\":null,"
          + "\"lastTickObservedAt\":null,"
          + "\"lastTickPremium\":null,"
          + "\"peakPremium\":null,"
          + "\"thresholdPremium\":null,"
          + "\"ticksReceived\":0"
          + "}";

  @Test
  void readsTheArmedTrailThroughTheRealTemporalConverter() {
    TrailingStateView v = convert(ARMED_JSON);

    assertThat(v.armed()).isTrue();
    assertThat(v.givebackPct()).isEqualByComparingTo(new BigDecimal("0.35"));
    // The fire trigger, taken from the workflow rather than re-derived. Note it is NOT
    // lastTickPremium x 0.65 (= 2.63 only by coincidence of this fixture's peak == last tick);
    // PositionsReaderTest pins the case where peak and last tick differ.
    assertThat(v.thresholdPremium()).isEqualByComparingTo(new BigDecimal("2.63"));
  }

  @Test
  void readsTheUnarmedShapeWithoutTrippingOnItsNulls() {
    TrailingStateView v = convert(UNARMED_JSON);

    assertThat(v.armed()).isFalse();
    assertThat(v.givebackPct()).isNull();
    assertThat(v.thresholdPremium()).isNull();
  }

  /**
   * The five fields this mirror does NOT model must not break it. This is the whole reason the
   * mirror carries {@code ignoreUnknown}: the same drift once dropped every position from /live.
   */
  @Test
  void toleratesOrchestratorFieldsTheMirrorDoesNotModel() {
    String withFutureField =
        ARMED_JSON.substring(0, ARMED_JSON.length() - 1) + ",\"someFutureField\":\"x\"}";

    TrailingStateView v = convert(withFutureField);

    assertThat(v.armed()).isTrue();
    assertThat(v.thresholdPremium()).isEqualByComparingTo(new BigDecimal("2.63"));
  }

  /** Exactly what {@code WorkflowStub.query("trailingState", TrailingStateView.class)} does. */
  private static TrailingStateView convert(String json) {
    return DefaultDataConverter.newDefaultInstance()
        .fromPayload(
            io.temporal.api.common.v1.Payload.newBuilder()
                .putMetadata("encoding", com.google.protobuf.ByteString.copyFromUtf8("json/plain"))
                .setData(com.google.protobuf.ByteString.copyFrom(json, StandardCharsets.UTF_8))
                .build(),
            TrailingStateView.class,
            TrailingStateView.class);
  }
}
