package com.ohmytradeagent.exec.broker.alpaca;

/**
 * Thrown by the P6-b credential write path (R-6.5) when a live-bound broker account ({@code
 * expected_account_id}) is already owned by a DIFFERENT tenant. A real brokerage account must bind
 * to at most one tenant — two live tenants sharing an account would double-size the same account.
 * The pre-persist check turns the cross-tenant collision into this clean, typed rejection (mapped
 * to a 409 by {@code BrokerCredentialAdminController}) instead of surfacing a raw partial-
 * unique-index violation from {@code broker_credentials_provider_account_uk}, which remains the
 * race-proof, fail-closed authority. The message names only the conflicting tenant + account +
 * provider — never any key material.
 */
public class DuplicateBrokerAccountException extends RuntimeException {
  public DuplicateBrokerAccountException(String message) {
    super(message);
  }
}
