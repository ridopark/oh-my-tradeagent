package com.ohmytradeagent.audit;

/**
 * A single completeness finding from {@link LedgerRederiver}. Issue #90 acceptance criterion 2
 * requires the verifier to "emit a structured diff identifying the missing event" — each {@code
 * Divergence} is one such entry.
 *
 * <p>{@code correlationId} is the {@code audit_event.correlation_id} of the affected lifecycle
 * (typically the {@code signal_id}); {@code kind} is the type of finding. {@code detail} is a
 * human-readable line that pinpoints which event is missing or unexpected — kept short and
 * structured (key=value pairs) so it round-trips legibly through Discord and CronJob logs.
 *
 * @param kind divergence kind enum
 * @param correlationId audit event correlation_id of the affected lifecycle (never null)
 * @param detail structured "key1=v1 key2=v2 ..." description of the finding (never null)
 */
public record Divergence(Kind kind, String correlationId, String detail) {

  public enum Kind {
    /** An ENTRY_KINDS event exists but no TERMINAL_CLOSE_KINDS event closes it. */
    MISSING_TERMINAL_CLOSE,
    /**
     * A PARTIAL_EXIT_REQUEST_KINDS event exists but no PARTIAL_EXIT_FILL_KINDS event matches it.
     */
    MISSING_PARTIAL_EXIT_FILL,
    /** A TERMINAL_CLOSE_KINDS event arrived with no preceding ENTRY_KINDS event. */
    ORPHAN_CLOSE_WITHOUT_ENTRY,
    /** A kind appeared that is not in {@link AuditEventKinds#ALL_KINDS}. */
    UNKNOWN_KIND
  }
}
