package com.ohmytradeagent.orchestrator.platform;

/**
 * Thrown by the P0c-a config write path when a compare-and-set UPDATE matches zero rows because the
 * stored {@code version} moved between the caller's read and the write. The row provably existed at
 * read time, so zero affected rows means a concurrent writer won — the caller must re-read and
 * retry against the fresh version.
 */
public class OptimisticLockException extends RuntimeException {
  public OptimisticLockException(String message) {
    super(message);
  }
}
