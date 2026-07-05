package com.ohmytradeagent.exec.broker.alpaca;

/**
 * Thrown by the P6-b credential write path (R-6.5) when a non-blank (real) broker account ({@code
 * expected_account_id}) is already owned by a DIFFERENT tenant. Uniqueness is enforced
 * per-broker-target for every non-blank account — keyed on the account being real, NOT on the pod
 * being live: a real brokerage account must bind to at most one tenant, else two tenants sharing an
 * account would double-size it. On the exec-alpaca-live DB this is the live double-bind guard; it
 * also holds on the paper DB. The pre-persist check (and, under a concurrent race, the UPSERT's
 * index-violation translation) turns the cross-tenant collision into this clean, typed rejection
 * (mapped to a 409 by {@code BrokerCredentialAdminController}) instead of surfacing a raw partial-
 * unique-index violation from {@code broker_credentials_provider_account_uk}, which remains the
 * race-proof, fail-closed authority. The message names only the conflicting/requesting tenant +
 * account + provider — never any key material.
 */
public class DuplicateBrokerAccountException extends RuntimeException {
  public DuplicateBrokerAccountException(String message) {
    super(message);
  }
}
