-- /live chain picker: today's watchlist as ONE shared daily list (not per tenant), so every
-- account's picker can highlight the author's ideas — including tenants that do not trade the
-- watchlist. Display-only: nothing in the trading path reads this table.
--
-- One row per ET date; the first clean post of the day wins (ON CONFLICT (et_date) DO NOTHING),
-- matching the sidecar's once-per-day mirror. `ideas` is the parsed list as the trigger session
-- maps it: [{ticker, right: C|P, strike, direction: ABOVE|BELOW, level}].
CREATE TABLE watchlist_daily (
  et_date            DATE         PRIMARY KEY,
  source_message_id  VARCHAR(32)  NOT NULL,
  author             VARCHAR(128),
  ideas              JSONB        NOT NULL,
  recorded_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The runtime role INSERTs (and needs SELECT for the ON CONFLICT arbiter on PG16); no UPDATE or
-- DELETE — the day's list is write-once.
GRANT SELECT, INSERT ON watchlist_daily TO orchestrator_runtime;

-- The tenant-dashboard BFF reads it as bff_readonly. That role is provisioned out of band (see
-- infra/secrets-template/secrets.template.yaml), so grant only where it exists — a dev or CI
-- database without it must still migrate.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'bff_readonly') THEN
    GRANT SELECT ON watchlist_daily TO bff_readonly;
  END IF;
END
$$;
