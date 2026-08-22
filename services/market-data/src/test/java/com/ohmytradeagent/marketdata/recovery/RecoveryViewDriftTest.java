package com.ohmytradeagent.marketdata.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.converter.DefaultDataConverter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * T10: the recovery transport mirrors must survive the LIVE query payloads through Temporal's REAL
 * data converter (mirrors the BFF's {@code PositionStateViewDriftTest} mechanism exactly).
 *
 * <p>This exists because every recovery unit test mocks the stub query — without this test,
 * deleting {@code @JsonIgnoreProperties(ignoreUnknown = true)} from a mirror survives the whole
 * suite while every real {@code exitProximity}/{@code positionState} query would throw in
 * production (Jackson fails on unknown properties by default), silently disabling recovery for
 * every armed trail.
 */
class RecoveryViewDriftTest {

  /** The 11-field shape the live orchestrator {@code exitProximity} query returns today. */
  private static final String ELEVEN_FIELD_EXIT_PROXIMITY_JSON =
      "{"
          + "\"contractSymbol\":\"TSLA  260918P00300000\","
          + "\"entryPremium\":2.70,"
          + "\"stopLevel\":1.35,"
          + "\"targetLevel\":5.40,"
          + "\"lastBid\":2.60,"
          + "\"lastTickPremium\":2.65,"
          + "\"peakPremium\":3.10,"
          + "\"trailingArmed\":true,"
          + "\"givebackPct\":0.35,"
          + "\"armed\":false,"
          + "\"lastTickAt\":\"2026-08-20T14:00:00Z\""
          + "}";

  /** The 5-field shape the live orchestrator {@code positionState} query returns today. */
  private static final String FIVE_FIELD_POSITION_STATE_JSON =
      "{"
          + "\"contractSymbol\":\"INTC  260618C00022000\","
          + "\"entryAt\":\"2026-06-30T14:31:00Z\","
          + "\"entryPremium\":1.23,"
          + "\"partialExited\":true,"
          + "\"remainingQty\":7"
          + "}";

  @Test
  void deserializesElevenFieldExitProximityIntoThreeFieldMirror() {
    DefaultDataConverter converter = DefaultDataConverter.newDefaultInstance();
    Payload payload = jsonPayload(ELEVEN_FIELD_EXIT_PROXIMITY_JSON);

    // Exactly what stub.query("exitProximity", ExitProximityViewMirror.class) does under the hood.
    ExitProximityViewMirror view =
        converter.fromPayload(
            payload, ExitProximityViewMirror.class, ExitProximityViewMirror.class);

    assertThat(view.contractSymbol()).isEqualTo("TSLA  260918P00300000");
    assertThat(view.trailingArmed()).isTrue();
    assertThat(view.armed()).isFalse();
  }

  @Test
  void deserializesFiveFieldPositionStateIntoTwoFieldMirror() {
    DefaultDataConverter converter = DefaultDataConverter.newDefaultInstance();
    Payload payload = jsonPayload(FIVE_FIELD_POSITION_STATE_JSON);

    PositionStateViewMirror view =
        converter.fromPayload(
            payload, PositionStateViewMirror.class, PositionStateViewMirror.class);

    assertThat(view.contractSymbol()).isEqualTo("INTC  260618C00022000");
    assertThat(view.remainingQty()).isEqualTo(7L);
  }

  /**
   * Control: an identical mirror WITHOUT {@code @JsonIgnoreProperties(ignoreUnknown=true)} fails on
   * the very same payload through the very same converter — proving the annotation is the
   * load-bearing piece, not some other converter setting.
   */
  @Test
  void sameConverterFailsOnUnannotatedExitProximityTwin() {
    DefaultDataConverter converter = DefaultDataConverter.newDefaultInstance();
    Payload payload = jsonPayload(ELEVEN_FIELD_EXIT_PROXIMITY_JSON);

    assertThatThrownBy(
            () ->
                converter.fromPayload(
                    payload,
                    StrictExitProximityViewMirror.class,
                    StrictExitProximityViewMirror.class))
        .isInstanceOf(DataConverterException.class);
  }

  @Test
  void sameConverterFailsOnUnannotatedPositionStateTwin() {
    DefaultDataConverter converter = DefaultDataConverter.newDefaultInstance();
    Payload payload = jsonPayload(FIVE_FIELD_POSITION_STATE_JSON);

    assertThatThrownBy(
            () ->
                converter.fromPayload(
                    payload,
                    StrictPositionStateViewMirror.class,
                    StrictPositionStateViewMirror.class))
        .isInstanceOf(DataConverterException.class);
  }

  private static Payload jsonPayload(String json) {
    return Payload.newBuilder()
        .putMetadata("encoding", com.google.protobuf.ByteString.copyFromUtf8("json/plain"))
        .setData(com.google.protobuf.ByteString.copyFrom(json, StandardCharsets.UTF_8))
        .build();
  }

  /** Deliberately UN-annotated twin used only to prove the control case fails. */
  private record StrictExitProximityViewMirror(
      String contractSymbol, boolean trailingArmed, boolean armed) {}

  /** Deliberately UN-annotated twin used only to prove the control case fails. */
  private record StrictPositionStateViewMirror(String contractSymbol, long remainingQty) {}
}
