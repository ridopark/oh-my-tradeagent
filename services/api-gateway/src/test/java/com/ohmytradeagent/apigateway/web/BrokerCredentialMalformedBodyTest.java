package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * UI-P2-a MF-7 regression: a malformed/unparseable request body must yield a DETAIL-FREE 400 and
 * never leak the api-key/secret. Deserialization fails in Jackson BEFORE the controller (and its
 * redacted record {@code toString}) runs, and Jackson's parse-error message can embed a fragment of
 * the source JSON — which on this route is the secret. {@link
 * GlobalExceptionHandler#unreadableBody} must catch it and emit no detail; this test posts a
 * malformed body containing the secret literal through the real MVC deserialization path and
 * asserts the secret appears in neither the response nor any log line.
 */
class BrokerCredentialMalformedBodyTest {

  private static final String API_SECRET = "ssshhh-this-is-the-broker-secret";

  private MockMvc mvc;
  private ListAppender<ILoggingEvent> logCapture;
  private Logger rootLogger;

  @BeforeEach
  void setUp() {
    // The controller deps are inert here: a malformed body never reaches the handler method.
    BrokerCredentialController controller =
        new BrokerCredentialController(
            mock(BrokerCredentialForwardService.class), new TenantContext("dev", "copytrade-v1"));
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    rootLogger = (Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
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
  void malformedBodyWithSecret_is400_noSecretInResponseOrLogs() throws Exception {
    // Truncated/invalid JSON that still embeds the secret literal in its source text.
    String malformed = "{\"api_secret_key\":\"" + API_SECRET + "\",\"tenant_id\":";

    MvcResult result =
        mvc.perform(
                post("/broker-credentials")
                    .header("X-Tenant-Id", "acme")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(malformed))
            .andExpect(status().isBadRequest())
            .andReturn();

    String responseBody = result.getResponse().getContentAsString();
    assertThat(responseBody).doesNotContain(API_SECRET);
    // Coarse: no parse-error detail echoed back.
    assertThat(responseBody).doesNotContain("detail");

    for (ILoggingEvent event : logCapture.list) {
      assertThat(event.getFormattedMessage()).doesNotContain(API_SECRET);
    }
  }
}
