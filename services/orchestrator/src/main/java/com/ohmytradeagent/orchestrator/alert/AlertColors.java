package com.ohmytradeagent.orchestrator.alert;

/**
 * Discord embed accent colors (decimal RGB) shared by the orchestrator trade alerters, per the
 * severity matrix: red = failure/rejected, green = accepted/success, yellow = warn/skip, blurple =
 * info. Centralised here so {@link OrderFailureAlerter} and {@link SignalFeedAlerter} reference one
 * source rather than re-declaring the literals.
 *
 * <p>The exec service's {@code BrokerRejectionAlerter} keeps its own red constant (cross-service;
 * no shared color holder spans the two services), and {@code WatchlistMirrorActivitiesImpl} keeps
 * its pre-existing green constant — both intentionally out of scope here.
 */
final class AlertColors {

  /** 0xED4245 — failure / rejection. */
  static final int RED = 15548997;

  /** 0x57F287 — accepted / success. */
  static final int GREEN = 5763719;

  /** 0xFEE75C — warn / skip. */
  static final int YELLOW = 16705372;

  /** 0x5865F2 — info. */
  static final int BLURPLE = 5793266;

  private AlertColors() {}
}
