-- R-6.5 cross-tenant account uniqueness (DARK by default — enforced only for
-- live-bound rows). A real brokerage account must bind to at most ONE tenant:
-- two live tenants sharing an expected_account_id would double-size the same
-- account. A PARTIAL unique index constrains only non-blank (live-bound) rows,
-- so multiple paper rows with a blank/NULL expected_account_id still coexist
-- (paper accounts are unconstrained). This is the race-proof, fail-closed
-- authority; the BrokerCredentialWriter pre-persist check turns the same
-- collision into a clean 409 instead of a raw constraint violation.
CREATE UNIQUE INDEX broker_credentials_provider_account_uk
  ON broker_credentials (provider, expected_account_id)
  WHERE expected_account_id IS NOT NULL AND expected_account_id <> '';
