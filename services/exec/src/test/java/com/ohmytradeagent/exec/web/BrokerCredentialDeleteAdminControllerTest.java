package com.ohmytradeagent.exec.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ohmytradeagent.exec.broker.alpaca.BrokerCredentialWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Handler behavior for the dark {@code DELETE /internal/broker-credentials} teardown route. The
 * standalone MockMvc setup exercises the controller in isolation (the dark-gate 404 and the
 * service-token 401 are proven by {@link BrokerCredentialDeleteDarkProofTest} and {@link
 * ExecAdminTokenFilterTest}, respectively — a standalone setup bypasses both the bean conditionals
 * and the servlet filter). The body carries no key material, so the response is a plain row count.
 */
class BrokerCredentialDeleteAdminControllerTest {

  private static final String BODY = "{\"tenant_id\":\"acme\",\"provider\":\"alpaca\"}";

  private BrokerCredentialWriter writer;
  private MockMvc mvc;
  private ListAppender<ILoggingEvent> logCapture;
  private Logger rootLogger;

  @BeforeEach
  void setUp() {
    writer = mock(BrokerCredentialWriter.class);
    BrokerCredentialDeleteAdminController controller =
        new BrokerCredentialDeleteAdminController(writer);
    mvc = MockMvcBuilders.standaloneSetup(controller).build();

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
  void happyPath_matchingTenant_delegatesToWriter_returnsDeletedCount() throws Exception {
    when(writer.delete("acme", "alpaca")).thenReturn(1);

    mvc.perform(
            delete("/internal/broker-credentials")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value(1));

    verify(writer).delete("acme", "alpaca");
  }

  @Test
  void absentRow_returnsZeroDeleted() throws Exception {
    when(writer.delete("acme", "alpaca")).thenReturn(0);

    mvc.perform(
            delete("/internal/broker-credentials")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deleted").value(0));
  }

  @Test
  void tenantMismatch_is403_andWriterNotCalled() throws Exception {
    mvc.perform(
            delete("/internal/broker-credentials")
                .header("X-Tenant-Id", "other-tenant")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isForbidden());

    verifyNoInteractions(writer);
  }

  @Test
  void blankProvider_is400_andWriterNotCalled() throws Exception {
    mvc.perform(
            delete("/internal/broker-credentials")
                .header("X-Tenant-Id", "acme")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenant_id\":\"acme\",\"provider\":\"  \"}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(writer);
  }

  @Test
  void missingTenantHeader_doesNotReachWriter() throws Exception {
    mvc.perform(
            delete("/internal/broker-credentials")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().is4xxClientError());

    verify(writer, org.mockito.Mockito.never()).delete(anyString(), anyString());
  }

  @Test
  void responseAndLogs_carryNoBodyEcho() throws Exception {
    // Non-secret route, but keep the discipline: the tenant/provider identifiers only, never a body
    // echo beyond the coarse count.
    when(writer.delete("acme", "alpaca")).thenReturn(1);

    String response =
        mvc.perform(
                delete("/internal/broker-credentials")
                    .header("X-Tenant-Id", "acme")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(response).doesNotContain("api_key");
    assertThat(response).doesNotContain("api_secret");
    for (ILoggingEvent event : logCapture.list) {
      assertThat(event.getFormattedMessage()).doesNotContain("api_secret");
    }
  }
}
