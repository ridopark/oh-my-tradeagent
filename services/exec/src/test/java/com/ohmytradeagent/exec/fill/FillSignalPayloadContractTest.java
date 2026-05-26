package com.ohmytradeagent.exec.fill;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.FillSignalPayload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.common.converter.DefaultDataConverter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire shape of the contract-owned {@link FillSignalPayload} DTO against Temporal's
 * default Jackson data converter. The orchestrator-side receivers (both {@code
 * CopytradeSignalWorkflow.onFill} and {@code PositionWorkflow.onFill}) reference the same generated
 * DTO, so the canary is now type identity rather than a hand-mirrored record. The JSON shape is
 * camelCase by {@code @JsonProperty} (see {@code contract/schemas/fill-signal-payload.json}).
 */
class FillSignalPayloadContractTest {

  @Test
  void payloadRoundTripsThroughTemporalDataConverter() {
    FillSignalPayload sent =
        new FillSignalPayload()
            .withBrokerOrderId("brk-7")
            .withFilledQty(42L)
            .withAvgFillPrice(new BigDecimal("3.14"))
            .withFilledAt(OffsetDateTime.parse("2026-05-24T01:23:45Z"));

    DefaultDataConverter converter = DefaultDataConverter.newDefaultInstance();
    Optional<Payloads> payloads = converter.toPayloads(sent);
    FillSignalPayload received =
        converter.fromPayloads(0, payloads, FillSignalPayload.class, FillSignalPayload.class);

    assertThat(received.getBrokerOrderId()).isEqualTo("brk-7");
    assertThat(received.getFilledQty()).isEqualTo(42L);
    assertThat(received.getAvgFillPrice()).isEqualByComparingTo(new BigDecimal("3.14"));
    assertThat(received.getFilledAt()).isEqualTo(OffsetDateTime.parse("2026-05-24T01:23:45Z"));
  }

  @Test
  void senderAndReceiverShareTheSameContractType() {
    // Sender (exec/FillDispatcherImpl) and receiver (orchestrator/CopytradeSignalWorkflow,
    // PositionWorkflow) both reference com.ohmytradeagent.contract.FillSignalPayload directly —
    // there is no hand-maintained mirror to drift. This identity check replaces the reflective
    // component-by-component equality the deleted mirror record used to enforce (issue #168).
    assertThat(FillSignalPayload.class.getName())
        .isEqualTo("com.ohmytradeagent.contract.FillSignalPayload");
  }
}
