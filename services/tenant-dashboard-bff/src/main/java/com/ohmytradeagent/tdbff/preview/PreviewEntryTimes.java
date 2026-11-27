package com.ohmytradeagent.tdbff.preview;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** When each position was first entered, from the audit trail, keyed by entry signal id. */
@Component
class PreviewEntryTimes {

  private final DSLContext orchestratorDsl;

  PreviewEntryTimes(@Qualifier("orchestratorDsl") DSLContext orchestratorDsl) {
    this.orchestratorDsl = orchestratorDsl;
  }

  /** Signal ids with no {@code EntryFilled} row (e.g. an adopted position) are simply absent. */
  Map<String, Instant> firstEntryAt(String tenantId, Collection<String> entrySignalIds) {
    Map<String, Instant> out = new HashMap<>();
    if (entrySignalIds.isEmpty()) {
      return out;
    }
    for (Record r :
        orchestratorDsl.fetch(
            "SELECT correlation_id, MIN(occurred_at) FROM audit_log"
                + " WHERE tenant_id = ? AND correlation_id = ANY(?) AND kind = 'EntryFilled'"
                + " GROUP BY correlation_id",
            tenantId,
            entrySignalIds.toArray(new String[0]))) {
      Timestamp at = r.get(1, Timestamp.class);
      if (at != null) {
        out.put(r.get(0, String.class), at.toInstant());
      }
    }
    return out;
  }
}
