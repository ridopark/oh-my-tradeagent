package com.ohmytradeagent.tdbff.trades;

// audit_log select MIRRORS TradesReader in this package — keep the two in sync. Narrowed to the
// entry-signal kinds (see the class javadoc).
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
 * <p>Two kinds, because neither alone covers every signal:
 *
 * <ul>
 *   <li>{@code SignalAccepted} is emitted AFTER contract resolution, so it carries the resolved
 *       {@code option_symbol} plus the sized {@code contracts}. BTO path only.
 *   <li>{@code SignalReceived} with {@code action=BTO} is emitted for EVERY entry signal, before
 *       any risk gate. It carries no OCC (the contract is not resolved yet) but does carry {@code
 *       ticker/expiry/strike/right/price}, which is what lets a signal REJECTED before resolution
 *       (MAX_POSITIONS_EXCEEDED, strategy disabled, ...) still be offered for a manual entry. The
 *       dashboard derives the OCC from those parts.
 * </ul>
 *
 * <p>An accepted signal therefore appears twice (received, then accepted). Deliberately dumb:
 * newest-first rows, no dedupe and no expiry filtering. Both are display concerns and live in the
 * dashboard, which already parses OCCs.
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

  private static final String KIND_SIGNAL_RECEIVED = "SignalReceived";

  private final DSLContext orchestratorDsl;

  public SignalsReader(@Qualifier("orchestratorDsl") DSLContext orchestratorDsl) {
    this.orchestratorDsl = orchestratorDsl;
  }

  /**
   * Received and accepted entry signals for the tenant across {@code strategyIds}, newest first.
   */
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
                .and(
                    DSL.field("kind")
                        .eq(KIND_SIGNAL_ACCEPTED)
                        .or(
                            DSL.field("kind")
                                .eq(KIND_SIGNAL_RECEIVED)
                                .and(DSL.field("subject->>'action'").eq("BTO")))))
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
