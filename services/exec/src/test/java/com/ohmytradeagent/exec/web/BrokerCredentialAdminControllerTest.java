package com.ohmytradeagent.exec.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ohmytradeagent.exec.broker.alpaca.BrokerCredentialWriter;
import com.ohmytradeagent.exec.broker.alpaca.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BrokerCredentialAdminControllerTest {

  private static final String API_KEY = "AKMY_SECRET_KEY_ID_12345";
  private static final String API_SECRET = "ssshhh-this-is-the-broker-secret";

  private static final String BODY =
      "{"
          + "\"tenant_id\":\"acme\","
          + "\"provider\":\"alpaca\","
          + "\"api_key_id\":\""
          + API_KEY
          + "\","
          + "\"api_secret_key\":\""
          + API_SECRET
          + "\","
          + "\"base_url\":\"https://paper-api.alpaca.markets\","
          + "\"ws_url\":\"wss://paper-api.alpaca.markets/stream\","
          + "\"declared_account_id\":\"acct-1\","
          + "\"expected_version\":0"
          + "}";

  private BrokerCredentialWriter writer;
  private MockMvc mvc;
  private ListAppender<ILoggingEvent> logCapture;
  private Logger rootLogger;

  @BeforeEach
  void setUp() {
    writer = mock(BrokerCredentialWriter.class);
    BrokerCredentialAdminController controller = new BrokerCredentialAdminController(writer);
    mvc = MockMvcBuilders.standaloneSetup(controller).build();

    // Capture every log line emitted while the controller runs so the negative MF-7 assertion can
    // prove the api-key/secret never reach a log appender.
    rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    logCapture = new ListAppender<>();
    logCapture.start();
    rootLogger.addAppender(logCapture);
    rootLogger.setLevel(Level.TRACE);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(logCapture);
  }

  @Test
  void happyPath_matchingTenant_delegatesToWriter_returnsVersion() throws Exception {
    when(writer.save(
            eq("acme"),
            eq("alpaca"),
            eq(API_KEY),
            eq(API_SECRET),
            eq("https://paper-api.alpaca.markets"),
            eq("wss://paper-api.alpaca.markets/stream"),
            eq("acct-1"),
            eq(0L),
            eq("acme")))
        .thenReturn(7L);

    mvc.perform(
            post("/internal/broker-credentials")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(7));

    verify(writer)
        .save(
            "acme",
            "alpaca",
            API_KEY,
            API_SECRET,
            "https://paper-api.alpaca.markets",
            "wss://paper-api.alpaca.markets/stream",
            "acct-1",
            0L,
            "acme");
    assertNoSecretInLogs();
  }

  @Test
  void tenantMismatch_is403_andWriterNotCalled() throws Exception {
    mvc.perform(
            post("/internal/broker-credentials")
                .header("X-Tenant-Id", "other-tenant")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isForbidden());

    verifyNoInteractions(writer);
    assertNoSecretInLogs();
  }

  @Test
  void optimisticLock_is409_andResponseHasNoSecret() throws Exception {
    stubSaveThrows(new OptimisticLockException("stale expectedVersion=0 for tenant=acme"));

    String response =
        mvc.perform(
                post("/internal/broker-credentials")
                    .header("X-Tenant-Id", "acme")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY))
            .andExpect(status().isConflict())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertResponseHasNoSecret(response);
    assertNoSecretInLogs();
  }

  @Test
  void writerRejection_is422_andResponseHasNoSecret() throws Exception {
    stubSaveThrows(
        new IllegalStateException(
            "keys authenticate account acct-999 not declared acct-1 " + API_KEY));

    String response =
        mvc.perform(
                post("/internal/broker-credentials")
                    .header("X-Tenant-Id", "acme")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY))
            .andExpect(status().isUnprocessableEntity())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertResponseHasNoSecret(response);
    assertNoSecretInLogs();
  }

  @Test
  void catchAll_is500_andResponseHasNoSecret() throws Exception {
    stubSaveThrows(new RuntimeException("kaboom " + API_SECRET));

    String response =
        mvc.perform(
                post("/internal/broker-credentials")
                    .header("X-Tenant-Id", "acme")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY))
            .andExpect(status().isInternalServerError())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertResponseHasNoSecret(response);
    assertNoSecretInLogs();
  }

  @Test
  void missingTenantHeader_doesNotReachWriter() throws Exception {
    // No X-Tenant-Id → Spring rejects with 400 (missing required header) before the body is read;
    // the writer is never invoked and nothing is logged.
    mvc.perform(
            post("/internal/broker-credentials")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().is4xxClientError());

    verify(writer, never())
        .save(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyLong(),
            anyString());
    assertNoSecretInLogs();
  }

  private void stubSaveThrows(Throwable t) {
    when(writer.save(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyLong(),
            anyString()))
        .thenThrow(t);
  }

  private void assertResponseHasNoSecret(String response) {
    assertThat(response).doesNotContain(API_KEY);
    assertThat(response).doesNotContain(API_SECRET);
  }

  private void assertNoSecretInLogs() {
    for (ILoggingEvent event : logCapture.list) {
      String rendered = event.getFormattedMessage();
      assertThat(rendered).doesNotContain(API_KEY);
      assertThat(rendered).doesNotContain(API_SECRET);
    }
  }
}
