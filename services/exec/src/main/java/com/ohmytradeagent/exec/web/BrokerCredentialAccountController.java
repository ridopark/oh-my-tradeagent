package com.ohmytradeagent.exec.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A1 (self-service-copytrade-onboarding) verified-account READ endpoint. {@code GET
 * /internal/broker-credentials/{tenant}/account?provider=alpaca} answers a single non-secret
 * question for the api-gateway arm-guard: does a verified broker account exist for this tenant?
 *
 * <ul>
 *   <li>{@code {"verified": true, "account": "<expected_account_id>"}} — a row exists with a
 *       non-blank {@code expected_account_id}.
 *   <li>{@code {"verified": false}} — no row, or the row's {@code expected_account_id} is blank.
 * </ul>
 *
 * <p><b>Sibling to the write path.</b> Sits under {@code /internal/broker-credentials} (so {@link
 * ExecAdminTokenFilter} bearer-gates it — the filter matches the route PREFIX) and is gated
 * identically to {@link BrokerCredentialAdminController} ({@code broker.creds.source=db} + an
 * {@code alpaca-*} impl), so it is dark on a homelab pod.
 *
 * <p><b>Secret hygiene.</b> Reads ONLY the non-secret {@code expected_account_id} column via {@link
 * BrokerCredentialAccountReader}; it NEVER decrypts, and no ciphertext/DEK/IV column is read.
 */
@RestController
@RequestMapping("/internal/broker-credentials")
@ConditionalOnExpression("'${broker.impl:}'.startsWith('alpaca-')")
@ConditionalOnProperty(name = "broker.creds.source", havingValue = "db")
public class BrokerCredentialAccountController {

  private final BrokerCredentialAccountReader reader;

  public BrokerCredentialAccountController(BrokerCredentialAccountReader reader) {
    this.reader = reader;
  }

  @GetMapping("/{tenant}/account")
  public ResponseEntity<Map<String, Object>> account(
      @PathVariable("tenant") String tenant,
      @RequestParam(value = "provider", defaultValue = "alpaca") String provider) {
    Optional<String> account = reader.verifiedAccount(tenant, provider);
    Map<String, Object> body = new LinkedHashMap<>();
    if (account.isPresent()) {
      body.put("verified", true);
      body.put("account", account.get());
    } else {
      body.put("verified", false);
    }
    return ResponseEntity.ok(body);
  }
}
