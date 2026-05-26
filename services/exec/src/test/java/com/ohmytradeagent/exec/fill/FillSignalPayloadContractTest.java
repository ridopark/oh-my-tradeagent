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
 * default Jackson data converter — both sender ({@code FillDispatcherImpl}) and receivers ({@code
 * CopytradeSignalWorkflow.onFill}, {@code PositionWorkflow.onFill}) reference the same generated
 * DTO, so the roundtrip here is the canary against accidental drift.
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
}
