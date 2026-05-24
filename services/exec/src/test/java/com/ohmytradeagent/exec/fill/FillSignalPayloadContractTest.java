package com.ohmytradeagent.exec.fill;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.api.common.v1.Payloads;
import io.temporal.common.converter.DefaultDataConverter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire shape of {@link FillSignalPayload} against Temporal's default Jackson data
 * converter. The receiver side (orchestrator's {@code FillEvent} record) deserialises this JSON by
 * field name; if the shape drifts, signal delivery breaks silently across the module boundary.
 *
 * <p>The local {@link FillEventMirror} record is a hand-maintained copy of {@code
 * com.ohmytradeagent.orchestrator.workflows.FillEvent} — orchestrator is not on exec's test
 * classpath. If anyone changes the receiver record, update this mirror too; the assertions here
 * become the canary that the two stay in lockstep.
 */
class FillSignalPayloadContractTest {

  /**
   * Field-for-field copy of {@code com.ohmytradeagent.orchestrator.workflows.FillEvent}. Must stay
   * identical: same component names, same component types, same order.
   */
  private record FillEventMirror(
      String brokerOrderId, long filledQty, BigDecimal avgFillPrice, OffsetDateTime filledAt) {}

  @Test
  void payloadRoundTripsThroughTemporalDataConverter() {
    FillSignalPayload sent =
        new FillSignalPayload(
            "brk-7", 42L, new BigDecimal("3.14"), OffsetDateTime.parse("2026-05-24T01:23:45Z"));

    DefaultDataConverter converter = DefaultDataConverter.newDefaultInstance();
    Optional<Payloads> payloads = converter.toPayloads(sent);
    FillEventMirror received =
        converter.fromPayloads(0, payloads, FillEventMirror.class, FillEventMirror.class);

    assertThat(received.brokerOrderId()).isEqualTo("brk-7");
    assertThat(received.filledQty()).isEqualTo(42L);
    assertThat(received.avgFillPrice()).isEqualByComparingTo(new BigDecimal("3.14"));
    assertThat(received.filledAt()).isEqualTo(OffsetDateTime.parse("2026-05-24T01:23:45Z"));
  }

  @Test
  void payloadAndMirrorHaveIdenticalRecordComponents() {
    var sentComponents = FillSignalPayload.class.getRecordComponents();
    var mirrorComponents = FillEventMirror.class.getRecordComponents();

    assertThat(sentComponents).hasSameSizeAs(mirrorComponents);
    for (int i = 0; i < sentComponents.length; i++) {
      assertThat(sentComponents[i].getName()).isEqualTo(mirrorComponents[i].getName());
      assertThat(sentComponents[i].getType()).isEqualTo(mirrorComponents[i].getType());
    }
  }
}
