package com.ohmytradeagent.orchestrator.platform;

/**
 * Thrown by the P0c-a config write path when the proposed {@code StrategyConfig} is malformed
 * (missing/null required field, schema_version newer than the build supports, failing live-required
 * gates, or a blob the live DB reader would fail-close on). Nothing is persisted; the live money
 * path never sees an invalid row.
 */
public class InvalidConfigException extends RuntimeException {
  public InvalidConfigException(String message) {
    super(message);
  }

  public InvalidConfigException(String message, Throwable cause) {
    super(message, cause);
  }
}
