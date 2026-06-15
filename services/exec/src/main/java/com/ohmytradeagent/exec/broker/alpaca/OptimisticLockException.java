package com.ohmytradeagent.exec.broker.alpaca;

/**
 * Thrown by the P6-b credential write path when the compare-and-set UPSERT matches zero rows
 * because the stored {@code version} did not equal the caller's {@code expectedVersion} — either a
 * concurrent writer moved it, or a blind first-write ({@code expectedVersion=0}) collided with an
 * already-present row. The caller must re-read the current version and retry. Mirrors the
 * orchestrator {@code StrategyConfigWriter}'s optimistic-lock idiom.
 */
public class OptimisticLockException extends RuntimeException {
  public OptimisticLockException(String message) {
    super(message);
  }
}
