-- Phase 2a cache for ContractActivities.resolve. Indexed by the deterministic OCC inputs.
-- strike_milli = strike * 1000, integer-encoded so we never use a floating-point PK column.
-- source distinguishes Phase 2a 'GENERATED' (deterministic OCC) from the Phase 4 'BROKER'
-- cross-check result (Open Question #9). Backfilling source on existing rows is safe at any time.
CREATE TABLE option_symbol_cache (
  tenant_id    VARCHAR(64)  NOT NULL,
  ticker       VARCHAR(16)  NOT NULL,
  expiry       DATE         NOT NULL,
  strike_milli BIGINT       NOT NULL,
  "right"      CHAR(1)      NOT NULL CHECK ("right" IN ('C','P')),
  occ_symbol   VARCHAR(32)  NOT NULL,
  source       VARCHAR(16)  NOT NULL,
  cached_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, ticker, expiry, strike_milli, "right")
);
