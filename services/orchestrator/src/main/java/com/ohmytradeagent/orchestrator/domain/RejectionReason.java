package com.ohmytradeagent.orchestrator.domain;

public enum RejectionReason {
  AUTHOR_NOT_WHITELISTED,
  SIGNAL_TOO_OLD,
  INVALID_TIMESTAMP,
  KILL_SWITCH_TRIPPED,
  KILL_SWITCH_UNAVAILABLE,
  MAX_POSITIONS_EXCEEDED
}
