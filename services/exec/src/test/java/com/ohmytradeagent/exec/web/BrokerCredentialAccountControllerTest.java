package com.ohmytradeagent.exec.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * A1 verified-account READ controller slice test against a mocked {@link
 * BrokerCredentialAccountReader}. Pins: an existing (non-blank) row → {@code
 * verified:true}+account; a missing/blank row → {@code verified:false} (no {@code account} key).
 * (The bearer-missing 401 is covered by {@link ExecAdminTokenFilterTest}; the dark bean-absence is
 * covered by {@link BrokerCredentialAccountDarkProofTest}.)
 */
class BrokerCredentialAccountControllerTest {

  private BrokerCredentialAccountReader reader;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    reader = mock(BrokerCredentialAccountReader.class);
    mvc = MockMvcBuilders.standaloneSetup(new BrokerCredentialAccountController(reader)).build();
  }

  @Test
  void existingRow_returnsVerifiedTrueAndAccount() throws Exception {
    when(reader.verifiedAccount(eq("acme"), eq("alpaca"))).thenReturn(Optional.of("847309116"));

    mvc.perform(get("/internal/broker-credentials/acme/account").param("provider", "alpaca"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(true))
        .andExpect(jsonPath("$.account").value("847309116"));
  }

  @Test
  void absentRow_returnsVerifiedFalse_noAccount() throws Exception {
    when(reader.verifiedAccount(eq("acme"), eq("alpaca"))).thenReturn(Optional.empty());

    mvc.perform(get("/internal/broker-credentials/acme/account").param("provider", "alpaca"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(false))
        .andExpect(jsonPath("$.account").doesNotExist());
  }

  @Test
  void providerDefaultsToAlpaca_whenParamOmitted() throws Exception {
    when(reader.verifiedAccount(eq("acme"), eq("alpaca"))).thenReturn(Optional.of("847309116"));

    mvc.perform(get("/internal/broker-credentials/acme/account"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(true))
        .andExpect(jsonPath("$.account").value("847309116"));
  }
}
