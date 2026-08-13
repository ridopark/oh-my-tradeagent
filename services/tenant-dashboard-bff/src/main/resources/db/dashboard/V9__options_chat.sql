-- Read-only mirror of a third-party Discord room, rendered at the dashboard's /options-chat page.
-- Plan: docs/plans/PLAN-2026-08-12-options-chat-mirror.md.
--
-- Phase 1 ships the STORE and the BFF write/read path only; nothing produces rows yet (the scraper
-- is Phase 2). The content is UNTRUSTED third-party text — it is stored as plain text and
-- structured entities, NEVER as HTML, so the renderer can never be handed markup to execute.
--
-- IDS ARE BIGINT, NOT TEXT. Discord snowflakes are 64-bit and monotonic with post time, so a BIGINT
-- primary key gives correct chronological ordering for free and keeps the pagination index narrow.
-- Storing them as TEXT would sort lexicographically, which only coincides with time order while
-- every id has the same digit count (Discord crossed 18->19 digits in 2021) — a latent
-- wrong-order-at-the-page-boundary bug. The BFF serializes these as JSON *strings* on the wire
-- because JavaScript numbers lose precision above 2^53 and would silently corrupt a 19-digit id.
--
-- IDENTITY, NOT BIGSERIAL, on the child tables. A non-owner INSERT into a BIGSERIAL column also
-- needs USAGE on the backing sequence (`GRANT USAGE ON SEQUENCE ... TO dashboard_writer`), which no
-- existing migration models here (V1 uses a composite PK, V4 uses gen_random_uuid()) and which is
-- therefore very easy to omit — it would fail at runtime with "permission denied for sequence".
-- GENERATED ALWAYS AS IDENTITY attaches the sequence to the column, so the table INSERT grant is
-- sufficient and the failure mode does not exist. OptionsChatMigrationIT proves this against a real
-- Postgres as dashboard_writer.

CREATE TABLE options_chat_message (
  message_id        BIGINT      PRIMARY KEY,        -- Discord snowflake
  channel_id        BIGINT      NOT NULL,
  author_name       TEXT        NOT NULL,
  author_avatar_url TEXT,
  posted_at         TIMESTAMPTZ NOT NULL,           -- from the message's time[datetime]
  content           TEXT        NOT NULL,           -- PLAIN TEXT ONLY, never HTML
  reply_to_id       BIGINT,                         -- snowflake this message replied to, if any
  edited            BOOLEAN     NOT NULL DEFAULT FALSE,
  deleted_at        TIMESTAMPTZ,                    -- set by the Phase 6 reconcile; row is kept
  content_hash      TEXT        NOT NULL,           -- edit detection without reading `content`
  ingested_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Matches the read endpoint's cursor EXACTLY: WHERE channel_id = ? [AND message_id < ?]
-- ORDER BY message_id DESC LIMIT ?. Ordering on the PK rather than posted_at keeps the cursor a
-- single opaque value and avoids ties (two messages can share a rendered timestamp; snowflakes are
-- unique). Named, because the migration structure tests assert index names.
CREATE INDEX options_chat_message_channel_id_message_id_idx
  ON options_chat_message (channel_id, message_id DESC);

-- Attachments and inline images. `bytes` stays NULL until Phase 4 fetches and transcodes the
-- original; the renderer shows a placeholder while fetch_state <> 'ok'. NEVER project `bytes` in a
-- list query — the BFF runs on a 768Mi limit with a ~192MB default heap and detoasting a page of
-- images would exhaust it. Media is read one row at a time by the /media/{id} route.
CREATE TABLE options_chat_attachment (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  message_id   BIGINT NOT NULL REFERENCES options_chat_message (message_id) ON DELETE CASCADE,
  ordinal      INT    NOT NULL,                     -- render order within the message
  kind         TEXT   NOT NULL,                     -- image | video | file | embed_image
  source_url   TEXT   NOT NULL,                     -- original signed Discord CDN url (expires)
  filename     TEXT,
  content_type TEXT,                                -- OUR transcode's type, never Discord's claim
  width        INT,
  height       INT,
  byte_size    INT,
  bytes        BYTEA,                               -- NULL until fetched
  fetch_state  TEXT   NOT NULL DEFAULT 'pending',   -- pending | ok | failed | skipped_too_large
  UNIQUE (message_id, ordinal)
);

-- Link previews / bot embeds, flattened to the fields the renderer actually shows.
CREATE TABLE options_chat_embed (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  message_id    BIGINT NOT NULL REFERENCES options_chat_message (message_id) ON DELETE CASCADE,
  ordinal       INT    NOT NULL,
  title         TEXT,
  description   TEXT,
  url           TEXT,
  author        TEXT,
  footer        TEXT,
  thumbnail_url TEXT,
  UNIQUE (message_id, ordinal)
);

-- GRANTS: dashboard_writer ONLY.
--
-- dashboard_readonly is deliberately NOT granted anything here. That role is the Next.js `pg` pool
-- (dashboard/lib/db.ts), which exists solely to resolve a login identity to its tenants; the BFF has
-- no DSL for it. /options-chat reads go through the BFF like every other domain read, so granting
-- the browser-facing role SELECT on untrusted third-party content would widen the blast radius of a
-- Next.js compromise for no benefit.
--
-- Table-level SELECT (not the column-scoped style of V7/V8) is correct here: V7/V8 scope columns to
-- keep PII in dashboard_user unreadable, whereas the read endpoint returns whole chat rows by
-- design. SELECT is load-bearing beyond the read path, per the PostgreSQL rule that a statement
-- needs SELECT on every column it *reads*, even inside a write:
--   * INSERT ... ON CONFLICT (message_id) DO NOTHING  -> the conflict probe reads the arbiter index.
--     Without SELECT this raises 42501; that exact failure is why the invite bind carries a
--     SAVEPOINT + swallow-23505 workaround (InviteWriterRepository). With SELECT granted, plain
--     ON CONFLICT works and no workaround is needed.
--
-- SELECT + INSERT is EXACTLY what the shipped code issues, and the grant stops there. Phase 4's
-- media fill (UPDATE ... SET bytes) and Phase 6's edit reconcile (UPDATE ... WHERE content_hash)
-- and retention (DELETE) each add their own grant when the code that needs it ships — mirroring V5,
-- which withheld DELETE until V7 actually needed it. Granting UPDATE now "because the next phase
-- will want it" is the same speculative widening this comment exists to refuse; OptionsChatMigrationIT
-- asserts UPDATE and DELETE are both denied, so a later phase has to widen deliberately.
GRANT SELECT, INSERT ON options_chat_message    TO dashboard_writer;
GRANT SELECT, INSERT ON options_chat_attachment TO dashboard_writer;
GRANT SELECT, INSERT ON options_chat_embed      TO dashboard_writer;
