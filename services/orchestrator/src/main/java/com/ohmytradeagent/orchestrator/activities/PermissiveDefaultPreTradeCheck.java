package com.ohmytradeagent.orchestrator.activities;

/**
 * Marker implemented by the permissive default {@code PreTradeCheckActivity} bean so callers can
 * detect at runtime when only the no-op default is wired (see {@link
 * RiskActivitiesImpl#assertPreTradeCheckRoutable}).
 */
public interface PermissiveDefaultPreTradeCheck {}
