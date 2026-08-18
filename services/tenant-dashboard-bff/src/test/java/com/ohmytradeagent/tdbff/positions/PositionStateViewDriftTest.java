package com.ohmytradeagent.tdbff.positions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ohmytradeagent.tdbff.positions.PositionsReader.PositionStateView;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.converter.DefaultDataConverter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Reproduces the prod incident where the dashboard showed 0 open positions for prod_real: the
 * orchestrator's live {@code positionState} query result grew from 3 to 5 fields ({@code entryAt},
 * {@code partialExited} were added), and {@link PositionStateView} — the BFF's hand-written
 * transport mirror — deserialized it through Temporal's data converter, which fails on unknown
 * properties (Jackson {@code FAIL_ON_UNKNOWN_PROPERTIES}, on by default). Every {@code
 * positionState} query threw, {@code valuePosition} caught it and returned null, so every position
 * was dropped.
 *
 * <p>The fix is {@code @JsonIgnoreProperties(ignoreUnknown = true)} on the mirror so the
 * orchestrator can add fields without re-breaking the BFF (transport-mirror pattern: the BFF
 * mirrors only the fields it needs). These tests drive the SAME converter the real query path uses
 * ({@link DefaultDataConverter}), so they prove the real mechanism, not a hand-built mapper.
 */
class PositionStateViewDriftTest {

  // The 5-field shape the live orchestrator now returns; the BFF mirror knows only 3 of them.
  private static final String FIVE_FIELD_JSON =
      "{"
          + "\"contractSymbol\":\"INTC  260618C00022000\","
          + "\"entryAt\":\"2026-06-30T14:31:00Z\","
          + "\"entryPremium\":1.23,"
          + "\"partialExited\":true,"
          + "\"remainingQty\":7"
          + "}";

  // The 8-field shape the orchestrator returns once it reports the operator trailing stop — what
  // /live's per-position stop badge reads.
  private static final String EIGHT_FIELD_ARMED_JSON =
      "{"
          + "\"contractSymbol\":\"INTC  260618C00022000\","
          + "\"entryAt\":\"2026-06-30T14:31:00Z\","
          + "\"entryPremium\":1.23,"
          + "\"partialExited\":false,"
          + "\"remainingQty\":7,"
          + "\"trailGivebackPct\":0.35,"
          + "\"trailStopPrice\":2.63,"
          + "\"trailingArmed\":true"
          + "}";

  @Test
  void deserializesTheArmedTrailingStateThroughTheSameConverter() {
    DefaultDataConverter converter = DefaultDataConverter.newDefaultInstance();

    PositionStateView state =
        converter.fromPayload(
            jsonPayload(EIGHT_FIELD_ARMED_JSON), PositionStateView.class, PositionStateView.class);

    assertThat(state.trailingArmed()).isTrue();
    assertThat(state.trailGivebackPct()).isEqualByComparingTo(new BigDecimal("0.35"));
    // Peak-anchored, carried through as-is. The BFF must never re-derive it from a live mark.
    assertThat(state.trailStopPrice()).isEqualByComparingTo(new BigDecimal("2.63"));
  }

  /**
   * Mixed-version guard, in the direction a roll actually produces: the orchestrator is still the
   * OLD 5-field build while the BFF already knows the trailing fields. Jackson leaves the defaults,
   * so the position renders UN-armed. That is the safe degradation — the dangerous failure would be
   * a badge asserting a stop the workflow does not hold.
   */
  @Test
  void legacyFiveFieldPayloadStillDeserializesAndReportsNoTrail() {
    DefaultDataConverter converter = DefaultDataConverter.newDefaultInstance();

    PositionStateView state =
        converter.fromPayload(
            jsonPayload(FIVE_FIELD_JSON), PositionStateView.class, PositionStateView.class);

    assertThat(state.contractSymbol()).isEqualTo("INTC  260618C00022000");
    assertThat(state.trailingArmed()).isFalse();
    assertThat(state.trailGivebackPct()).isNull();
    assertThat(state.trailStopPrice()).isNull();
  }

  @Test
  void deserializesPositionStateWithExtraOrchestratorFieldsViaTemporalConverter() {
    DefaultDataConverter converter = DefaultDataConverter.newDefaultInstance();
    Payload payload = jsonPayload(FIVE_FIELD_JSON);

    // This is exactly what WorkflowStub.query("positionState", PositionStateView.class) does under
    // the hood: fromPayload(payload, PositionStateView.class, PositionStateView.class).
    PositionStateView state =
        converter.fromPayload(payload, PositionStateView.class, PositionStateView.class);

    assertThat(state.contractSymbol()).isEqualTo("INTC  260618C00022000");
    assertThat(state.remainingQty()).isEqualTo(7L);
    assertThat(state.entryPremium()).isEqualByComparingTo(new BigDecimal("1.23"));
  }

  /**
   * Control: an identical 3-field mirror WITHOUT {@code @JsonIgnoreProperties(ignoreUnknown=true)}
   * fails on the very same payload through the very same converter — proving the annotation is the
   * load-bearing fix, not some other converter setting.
   */
  @Test
  void sameConverterFailsOnUnknownFieldsWithoutIgnoreUnknown() {
    DefaultDataConverter converter = DefaultDataConverter.newDefaultInstance();
    Payload payload = jsonPayload(FIVE_FIELD_JSON);

    assertThatThrownBy(
            () ->
                converter.fromPayload(
                    payload, StrictPositionStateView.class, StrictPositionStateView.class))
        .isInstanceOf(DataConverterException.class);
  }

  private static Payload jsonPayload(String json) {
    return Payload.newBuilder()
        .putMetadata("encoding", com.google.protobuf.ByteString.copyFromUtf8("json/plain"))
        .setData(com.google.protobuf.ByteString.copyFrom(json, StandardCharsets.UTF_8))
        .build();
  }

  /** Deliberately UN-annotated 3-field mirror used only to prove the control case fails. */
  private record StrictPositionStateView(
      String contractSymbol,
      @JsonProperty("remainingQty") long remainingQty,
      BigDecimal entryPremium) {}
}
