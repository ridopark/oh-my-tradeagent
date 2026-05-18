package com.ohmytradeagent.orchestrator.activities;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Issue #88: per-(tenant, strategy) JSON snapshot of the last-loaded {@code StrategyConfig} map,
 * used by {@link TenantConfigChangedEmitter} to compute the diff at orchestrator boot.
 *
 * <p>The snapshot file is a diff cache, NOT the source of truth (the audit log is). Losing it
 * (volume rebuild) re-arms a one-time false-negative — the next boot diffs against the new state
 * and finds no changes. This is acceptable because the runbook flow captures the audit row at
 * flip-time and incident review reads the audit log, not the snapshot.
 *
 * <p>Writes are atomic via tmp-file + ATOMIC_MOVE so an interrupted write never leaves a
 * half-snapshot. Reads return {@link Optional#empty()} on file-not-found and on JSON-parse failure
 * (the corrupted file is treated as "no prior snapshot"; the caller will overwrite it with the
 * current state — see {@code corruptPriorSnapshot_doesNotEmit_andOverwrites} unit test).
 */
public final class TenantConfigSnapshot {

  private static final Logger log = LoggerFactory.getLogger(TenantConfigSnapshot.class);

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ObjectMapper objectMapper;
  private final Path baseDir;

  public TenantConfigSnapshot(ObjectMapper objectMapper, Path baseDir) {
    this.objectMapper = objectMapper;
    this.baseDir = baseDir;
  }

  /**
   * Reads the snapshot for {@code (tenantId, strategyId)}. Returns {@link Optional#empty()} when
   * the file does not exist OR when it exists but cannot be parsed as JSON; a corrupted snapshot is
   * treated as "no prior snapshot" so the caller emits no event and overwrites the corrupt file
   * with the current state.
   */
  public Optional<Map<String, Object>> load(String tenantId, String strategyId) {
    Path file = path(tenantId, strategyId);
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    try {
      byte[] bytes = Files.readAllBytes(file);
      return Optional.of(objectMapper.readValue(bytes, MAP_TYPE));
    } catch (IOException e) {
      log.warn(
          "corrupt or unreadable tenant-config snapshot at {} (tenant={} strategy={}); treating as first-boot and overwriting",
          file,
          tenantId,
          strategyId,
          e);
      return Optional.empty();
    }
  }

  /**
   * Writes the snapshot for {@code (tenantId, strategyId)} atomically. Creates parent directories
   * as needed. Uses a sibling temp file + ATOMIC_MOVE so partial writes are never observable.
   */
  public void store(String tenantId, String strategyId, Map<String, Object> snapshot)
      throws IOException {
    Path dest = path(tenantId, strategyId);
    Files.createDirectories(dest.getParent());
    Path tmp = Files.createTempFile(dest.getParent(), dest.getFileName().toString() + ".", ".tmp");
    try {
      byte[] bytes = objectMapper.writeValueAsBytes(snapshot);
      Files.write(tmp, bytes);
      try {
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException atomicFailed) {
        // Some filesystems (notably tmpfs on certain kernels) don't support ATOMIC_MOVE
        // across directory boundaries; fall back to a non-atomic replace. The temp file lives
        // in the same parent directory so this should rarely trip.
        log.debug(
            "ATOMIC_MOVE not supported for {} → {}; falling back to REPLACE_EXISTING",
            tmp,
            dest,
            atomicFailed);
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      // If move succeeded, tmp no longer exists; if anything threw, clean up.
      try {
        Files.deleteIfExists(tmp);
      } catch (IOException cleanupFailed) {
        log.debug("could not clean up temp snapshot {}", tmp, cleanupFailed);
      }
    }
  }

  /** Visible for {@link TenantConfigChangedEmitter} and tests; not part of the public read API. */
  Path path(String tenantId, String strategyId) {
    return baseDir.resolve(tenantId).resolve(strategyId + ".json");
  }

  /**
   * Canonicalize a StrategyConfig pojo to a flat {@code Map<String, Object>} for diffing.
   *
   * <p>Round-trips through JSON bytes (not the cheaper {@code convertValue}) so the resulting map
   * is structurally identical to a map re-read from a stored snapshot file: Jackson's default
   * deserializer for {@code Object} maps JSON numbers to {@code Integer} when they fit and {@code
   * Long} otherwise, but {@code convertValue} on a POJO with a {@code Long} field would leave the
   * {@code Long} as-is. Mixing the two would produce false-positive diffs (e.g. {@code Integer 5}
   * != {@code Long 5}). Round-tripping forces both sides through the same deserializer path.
   */
  public static Map<String, Object> canonicalize(ObjectMapper objectMapper, Object config) {
    try {
      byte[] bytes = objectMapper.writeValueAsBytes(config);
      return objectMapper.readValue(bytes, MAP_TYPE);
    } catch (IOException e) {
      throw new IllegalStateException("failed to canonicalize StrategyConfig", e);
    }
  }
}
