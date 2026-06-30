package com.ohmytradeagent.tdbff.proximity;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.tdbff.proximity.ProximityReader.EntryProximityView;
import com.ohmytradeagent.tdbff.proximity.ProximityReader.ExitProximityView;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DefaultDataConverter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Sibling of {@link com.ohmytradeagent.tdbff.positions.PositionStateViewDriftTest}: the {@code
 * entryProximity}/{@code exitProximity} query results are also hand-written transport mirrors of
 * orchestrator query shapes deserialized through Temporal's data converter (which fails on unknown
 * properties). They are exposed to the same additive-field drift that broke {@code positionState},
 * so they carry {@code @JsonIgnoreProperties(ignoreUnknown = true)} too. These drive the real
 * {@link DefaultDataConverter} path with an extra unknown field present.
 */
class ProximityViewDriftTest {

  @Test
  void entryProximityIgnoresUnknownFields() {
    DefaultDataConverter converter = DefaultDataConverter.newDefaultInstance();
    String json =
        "{"
            + "\"ticker\":\"NVDA\","
            + "\"direction\":\"ABOVE\","
            + "\"triggerLevel\":140.0,"
            + "\"bandLow\":139.0,"
            + "\"bandHigh\":141.0,"
            + "\"lastPrice\":138.5,"
            + "\"state\":\"ARMED\","
            + "\"optionSymbol\":\"NVDA  260516C00140000\","
            + "\"armedAt\":\"2026-06-30T13:00:00Z\""
            + "}";

    EntryProximityView v =
        converter.fromPayload(
            jsonPayload(json), EntryProximityView.class, EntryProximityView.class);

    assertThat(v.ticker()).isEqualTo("NVDA");
    assertThat(v.direction()).isEqualTo("ABOVE");
    assertThat(v.optionSymbol()).isEqualTo("NVDA  260516C00140000");
  }

  @Test
  void exitProximityIgnoresUnknownFields() {
    DefaultDataConverter converter = DefaultDataConverter.newDefaultInstance();
    String json =
        "{"
            + "\"contractSymbol\":\"NVDA  260516C00140000\","
            + "\"entryPremium\":2.50,"
            + "\"stopLevel\":1.80,"
            + "\"targetLevel\":4.00,"
            + "\"lastBid\":2.70,"
            + "\"lastTickPremium\":2.71,"
            + "\"peakPremium\":3.10,"
            + "\"trailingArmed\":true,"
            + "\"givebackPct\":0.25,"
            + "\"armed\":true,"
            + "\"lastTickAt\":\"2026-06-30T13:00:00Z\","
            + "\"someNewField\":\"future\""
            + "}";

    ExitProximityView v =
        converter.fromPayload(jsonPayload(json), ExitProximityView.class, ExitProximityView.class);

    assertThat(v.contractSymbol()).isEqualTo("NVDA  260516C00140000");
    assertThat(v.armed()).isTrue();
    assertThat(v.entryPremium()).isEqualByComparingTo("2.50");
  }

  private static Payload jsonPayload(String json) {
    return Payload.newBuilder()
        .putMetadata("encoding", com.google.protobuf.ByteString.copyFromUtf8("json/plain"))
        .setData(com.google.protobuf.ByteString.copyFrom(json, StandardCharsets.UTF_8))
        .build();
  }
}
