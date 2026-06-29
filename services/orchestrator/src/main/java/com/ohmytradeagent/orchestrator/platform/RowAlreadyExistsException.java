package com.ohmytradeagent.orchestrator.platform;

/**
 * Thrown by the Phase I-1b create-tenant path when the {@code INSERT ... ON CONFLICT (tenant_id,
 * strategy_id) DO NOTHING} affects zero rows — a config row already exists for the (tenant,
 * strategy). The create activity coarsens this into the {@code ALREADY_EXISTS} outcome → HTTP 409,
 * so a duplicate create is reported deterministically rather than silently overwriting or
 * appending.
 */
public class RowAlreadyExistsException extends RuntimeException {
  public RowAlreadyExistsException(String message) {
    super(message);
  }
}
