package com.ohmytradeagent.exec.fill;

/**
 * Routes a {@link BrokerFillEvent} to the workflow that owns the originating order intent. Single
 * dispatch path so the WebSocket listener and the polling fallback share dedup + idempotency
 * semantics. Implementations are expected to be at-least-once (callers MUST tolerate replays) and
 * must not throw on benign "workflow already completed" cases — those are logged + counted, not
 * propagated.
 */
public interface FillDispatcher {

  void dispatch(BrokerFillEvent event);
}
