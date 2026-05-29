package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.orchestrator.alert.OrderFailureAlerter;
import java.time.OffsetDateTime;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

class AuditActivitiesImplTest {

  private ObjectMapper objectMapper;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    Logger logger = (Logger) LoggerFactory.getLogger(AuditActivitiesImpl.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    logger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    Logger logger = (Logger) LoggerFactory.getLogger(AuditActivitiesImpl.class);
    logger.detachAppender(logAppender);
    logAppender.stop();
  }

  @Test
  void logBypassesChainWriterAndInsertsNullHashesWhenDisabled() throws Exception {
    DSLContext dsl = mock(DSLContext.class);
    AuditLogChainWriter chainWriter = mock(AuditLogChainWriter.class);

    AuditActivitiesImpl activities =
        new AuditActivitiesImpl(
            dsl, objectMapper, chainWriter, /* chainWriterEnabled= */ false, noopAlerter());

    AuditEvent event = buildEvent();
    assertThatCode(() -> activities.log(event)).doesNotThrowAnyException();

    // computeRowHash must never be called when the chain writer is disabled.
    verify(chainWriter, never()).computeRowHash(any(), any());

    // Only the INSERT must be executed (no advisory-lock SQL when chain writer is disabled).
    ArgumentCaptor<Object[]> bindingsCaptor = ArgumentCaptor.forClass(Object[].class);
    verify(dsl, times(1)).execute(anyString(), bindingsCaptor.capture());
    Object[] bindings = bindingsCaptor.getValue();
    // INSERT has 12 positional bindings; prev_hash is index 10, row_hash is index 11.
    assertThat(bindings[10]).isNull();
    assertThat(bindings[11]).isNull();
  }

  @Test
  void logFallsBackToNullHashesAndWarnsWhenChainWriterThrows() throws Exception {
    DSLContext dsl = mock(DSLContext.class);
    AuditLogChainWriter chainWriter = mock(AuditLogChainWriter.class);
    when(chainWriter.computeRowHash(any(), any()))
        .thenThrow(new IllegalArgumentException("test: bad subject"));

    AuditActivitiesImpl activities =
        new AuditActivitiesImpl(
            dsl, objectMapper, chainWriter, /* chainWriterEnabled= */ true, noopAlerter());

    AuditEvent event = buildEvent();
    assertThatCode(() -> activities.log(event)).doesNotThrowAnyException();

    // Two execute() calls: advisory-lock SQL + INSERT.
    // The INSERT is the second call and has 12 bindings (prev_hash at index 10, row_hash at 11).
    ArgumentCaptor<Object[]> bindingsCaptor = ArgumentCaptor.forClass(Object[].class);
    verify(dsl, times(2)).execute(anyString(), bindingsCaptor.capture());
    Object[] insertBindings = bindingsCaptor.getAllValues().get(1);
    assertThat(insertBindings[10]).isNull();
    assertThat(insertBindings[11]).isNull();

    // A WARN log starting with "chain-restart-after-failure:" must have been emitted.
    boolean warnEmitted =
        logAppender.list.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.WARN
                        && e.getFormattedMessage().startsWith("chain-restart-after-failure:"));
    assertThat(warnEmitted).as("chain-restart WARN log must fire").isTrue();
  }

  @Test
  void webhookFailureDoesNotBreakAuditWrite() throws Exception {
    DSLContext dsl = mock(DSLContext.class);
    AuditLogChainWriter chainWriter = mock(AuditLogChainWriter.class);

    // A real alerter whose webhook ALWAYS throws — simulates Discord being down (the #295 class).
    // The audit INSERT must still execute and log() must not propagate the failure.
    OrderFailureAlerter throwingAlerter =
        new OrderFailureAlerter(
            content -> {
              throw new RuntimeException("discord down");
            },
            "SignalRejected,OrphanSTC,EntryExpired");

    AuditActivitiesImpl activities =
        new AuditActivitiesImpl(
            dsl, objectMapper, chainWriter, /* chainWriterEnabled= */ false, throwingAlerter);

    AuditEvent event = buildEvent();
    event.setKind("SignalRejected"); // allowlisted → alerter attempts (and fails) the webhook
    assertThatCode(() -> activities.log(event)).doesNotThrowAnyException();

    // The audit INSERT still ran exactly once despite the webhook blowing up.
    verify(dsl, times(1)).execute(anyString(), any(Object[].class));
  }

  /** Empty-allowlist alerter so the pre-existing chain-writer tests exercise only the DB path. */
  private static OrderFailureAlerter noopAlerter() {
    return new OrderFailureAlerter(content -> {}, "");
  }

  private static AuditEvent buildEvent() {
    AuditEvent ev = new AuditEvent();
    ev.setSchemaVersion(1L);
    ev.setTenantId("dev");
    ev.setStrategyId("copytrade-v1");
    ev.setEventId("00000000-0000-4000-8000-00000000aaaa");
    ev.setOccurredAt(OffsetDateTime.parse("2026-05-18T07:00:00Z"));
    ev.setKind("SignalReceived");
    ev.setActor("acme_trader");
    ev.setWorkflowId("wf-001");
    ev.setCorrelationId("corr-001");
    ev.setSubject(Map.of("signal_id", "111:0"));
    return ev;
  }
}
