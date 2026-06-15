-- Multi-tenant P6-a: encrypted-DB broker credentials (DARK by default —
-- no cluster sets broker.creds.source=db yet). Per-tenant broker API keys live
-- as an envelope-encrypted blob: a per-row 256-bit AES-GCM DEK encrypts the
-- packed (api-key-id || api-secret-key) plaintext, and that DEK is itself
-- AES-GCM-wrapped under a process-wide KEK delivered as a static k8s Secret.
-- The KEK never lands in this table; kek_version selects which KEK unwraps the
-- row's DEK so a KEK rotation is a per-row, not whole-table, re-encrypt.
--
-- AAD binds the ciphertext to (tenant_id || provider || expected_account_id ||
-- kek_version) so a cross-tenant blob swap fails GCM verification (fail-closed).
CREATE TABLE broker_credentials (
  tenant_id           VARCHAR(64)  NOT NULL,
  provider            VARCHAR(32)  NOT NULL,
  ciphertext          BYTEA        NOT NULL,
  iv                  BYTEA        NOT NULL,
  wrapped_dek         BYTEA        NOT NULL,
  dek_iv              BYTEA        NOT NULL,
  kek_version         INTEGER      NOT NULL,
  base_url            VARCHAR(255) NOT NULL,
  ws_url              VARCHAR(255),
  expected_account_id VARCHAR(64),
  version             BIGINT       NOT NULL DEFAULT 1,
  updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_by          VARCHAR(128) NOT NULL,
  PRIMARY KEY (tenant_id, provider)
);
