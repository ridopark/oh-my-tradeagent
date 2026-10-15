-- V10 sized source_message_id for a bare Discord snowflake (~19 chars), but the sidecar's id is
-- "chat-messages-<channel>-<message>" (51 chars), so every insert failed "value too long" and the
-- picker never saw a watchlist (2026-10-15). 128 leaves room for any id the sidecar forms.
ALTER TABLE watchlist_daily ALTER COLUMN source_message_id TYPE VARCHAR(128);
