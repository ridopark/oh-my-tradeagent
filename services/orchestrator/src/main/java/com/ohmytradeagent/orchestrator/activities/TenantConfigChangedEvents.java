package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.AuditEvent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Shared factory for {@code TenantConfigChanged} audit events (issue #88 shape). Both producers of
 * this event emit an identical {@link AuditEvent} structure through this one path:
 *
 * <ul>
 *   <li>{@link TenantConfigChangedEmitter} — the boot-time ConfigMap-reload diff emitter ({@code
 *       source=configmap-reload}, no version columns).
 *   <li>{@code StrategyConfigWriter} — the P0c-a runtime config write path ({@code
 *       source=runtime-write}, {@code old_version}/{@code new_version} populated).
 * </ul>
 *
 * <p>Extracted so the diff/canonicalize/redaction logic lives in exactly one place — a divergence
 * between the two producers' event shapes would corrupt operator forensics. The {@code AuditEvent}
 * returned here MUST be emitted via {@link AuditActivities#log(AuditEvent)} so the {@code
 * AuditLogChainWriter} populates {@code prev_hash}/{@code row_hash}; never INSERT it directly.
 */
public final class TenantConfigChangedEvents {

  static final String KIND = "TenantConfigChanged";

  private TenantConfigChangedEvents() {}

  /**
   * Returns the sorted list of keys whose values differ between {@code prior} and {@code current},
   * over the union of both keysets. {@code null}-vs-missing is treated as a no-change (a key
   * present-with-null on one side and absent on the other is not interesting to operators).
   */
  public static List<String> diffKeys(Map<String, Object> prior, Map<String, Object> current) {
    Set<String> union = new TreeSet<>(prior.keySet());
    union.addAll(current.keySet());
    List<String> out = new ArrayList<>();
    for (String key : union) {
      Object p = prior.get(key);
      Object c = current.get(key);
      if (!Objects.equals(p, c)) {
        out.add(key);
      }
    }
    return out;
  }

  /**
   * Builds the {@code old_values}/{@code new_values} map: for each changed key NOT in {@code
   * redactedKeys}, include the key + its value from {@code source}; redacted keys are omitted
   * entirely (the key still appears in {@code changed_keys} so the operator knows it changed).
   */
  public static Map<String, Object> redactedView(
      Map<String, Object> source, List<String> changedKeys, Set<String> redactedKeys) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (String key : changedKeys) {
      if (redactedKeys.contains(key)) {
        continue;
      }
      if (source.containsKey(key)) {
        out.put(key, source.get(key));
      }
    }
    return out;
  }

  /**
   * Builds a fully-populated {@code TenantConfigChanged} {@link AuditEvent} from the canonical
   * prior and current config maps. Computes {@code changed_keys} and the redacted old/new value
   * maps via {@link #diffKeys} + {@link #redactedView} so both producers share one diff/redaction
   * path.
   *
   * @param tenantId tenant key (subject {@code tenant_id} + event tenant_id)
   * @param strategyId strategy key (subject {@code strategy_id} + event strategy_id)
   * @param actor who caused the change (subject {@code actor} + event actor)
   * @param source provenance label, e.g. {@code configmap-reload} or {@code runtime-write}
   * @param oldVersion the row {@code version} before the write, or {@code null} to omit the key
   * @param newVersion the row {@code version} after the write, or {@code null} to omit the key
   * @param prior canonical map of the prior config
   * @param current canonical map of the current config
   * @param redactedKeys keys whose values must be omitted from old/new value maps
   */
  public static AuditEvent build(
      String tenantId,
      String strategyId,
      String actor,
      String source,
      Long oldVersion,
      Long newVersion,
      Map<String, Object> prior,
      Map<String, Object> current,
      Set<String> redactedKeys) {
    List<String> changedKeys = diffKeys(prior, current);
    Map<String, Object> oldValues = redactedView(prior, changedKeys, redactedKeys);
    Map<String, Object> newValues = redactedView(current, changedKeys, redactedKeys);

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("tenant_id", tenantId);
    subject.put("strategy_id", strategyId);
    subject.put("actor", actor);
    subject.put("source", source);
    if (oldVersion != null) {
      subject.put("old_version", oldVersion);
    }
    if (newVersion != null) {
      subject.put("new_version", newVersion);
    }
    subject.put("changed_keys", changedKeys);
    subject.put("old_values", oldValues);
    subject.put("new_values", newValues);
    subject.put("loaded_at", now);

    AuditEvent event = new AuditEvent();
    event.setSchemaVersion(1L);
    event.setTenantId(tenantId);
    event.setStrategyId(strategyId);
    event.setEventId(UUID.randomUUID().toString());
    event.setOccurredAt(now);
    event.setKind(KIND);
    event.setActor(actor);
    event.setCorrelationId(tenantId + "/" + strategyId);
    event.setSubject(subject);
    return event;
  }
}
