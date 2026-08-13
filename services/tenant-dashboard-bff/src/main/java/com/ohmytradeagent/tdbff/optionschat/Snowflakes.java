package com.ohmytradeagent.tdbff.optionschat;

/**
 * Discord snowflake parsing, shared by the ingest parser and the read endpoint's page cursor.
 *
 * <p>They parse the SAME value space — the cursor a client sends back is a {@code message_id} this
 * feature ingested — so they have to agree. Two independent copies of "trim, parse, reject
 * non-positive" would drift the first time the rule tightened, producing a cursor the read accepts
 * but the store never wrote.
 *
 * <p>Snowflakes cross the wire as STRINGS: they exceed 2^53, so a JSON number would already have
 * lost precision in the browser before it reached us.
 */
public final class Snowflakes {

  private Snowflakes() {}

  /**
   * The snowflake in {@code raw}, or {@code null} if it is absent, unparseable, or non-positive.
   */
  public static Long parse(Object raw) {
    if (raw == null) {
      return null;
    }
    try {
      long parsed = raw instanceof Number n ? n.longValue() : Long.parseLong(raw.toString().trim());
      return parsed > 0 ? parsed : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
