package com.ohmytradeagent.tdbff.trades;

// audit_log select MIRRORS TradesReader in this package — keep the two in sync. Narrowed to the one
// kind that carries a RESOLVED contract for an entry the system accepted.
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Recently signalled ENTRY contracts, for the /live manual-entry contract picker.
 *
 * <p>{@code SignalAccepted} is the only kind that fits: it is emitted by CopytradeSignalWorkflow
 * AFTER contract resolution, so it carries a resolved {@code option_symbol} (verified against live
 * audit rows — {@code SignalReceived} carries none, because the OCC does not exist yet at that
 * point), and it is emitted independently of whether the entry ever filled, so a signal that was
 * accepted but never filled still appears. It is emitted on the BTO path only, so nothing here is
 * an exit contract.
 *
 * <p>Deliberately dumb: newest-first rows, no dedupe and no expiry filtering. Both are display
 * concerns and live in the dashboard, which already parses OCCs.
 */
@Component
public class SignalsReader {

  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 200;

  /**
   * Kept as a literal for the same reason TradesReader does it: the BFF stays dependency-light
   * (contract-java only, not the whole audit module).
   */
  private static final String KIND_SIGNAL_ACCEPTED = "SignalAccepted";

  private final DSLContext orchestratorDsl;

  public SignalsReader(@Qualifier("orchestratorDsl") DSLContext orchestratorDsl) {
    this.orchestratorDsl = orchestratorDsl;
  }

  /** Accepted entry signals for the tenant across {@code strategyIds}, newest first. */
  public List<Map<String, Object>> signals(String tenantId, List<String> strategyIds, int limit) {
    if (strategyIds.isEmpty()) {
      return List.of();
    }
    int cappedLimit = Math.max(1, Math.min(MAX_LIMIT, limit <= 0 ? DEFAULT_LIMIT : limit));
    return orchestratorDsl
        .select(
            DSL.field("occurred_at", OffsetDateTime.class),
            DSL.field("strategy_id"),
            DSL.field("subject").cast(String.class).as("subject_json"))
        .from(DSL.table("audit_log"))
        .where(
            DSL.field("tenant_id")
                .eq(tenantId)
                .and(DSL.field("strategy_id").in(strategyIds))
                .and(DSL.field("kind").eq(KIND_SIGNAL_ACCEPTED)))
        .orderBy(DSL.field("occurred_at").desc(), DSL.field("id").desc())
        .limit(cappedLimit)
        .fetch()
        .stream()
        .map(SignalsReader::row)
        .toList();
  }

  private static Map<String, Object> row(Record r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("occurred_at", r.get("occurred_at"));
    m.put("strategy_id", r.get("strategy_id"));
    m.put("subject", r.get("subject_json"));
    return Collections.unmodifiableMap(m);
  }
}
