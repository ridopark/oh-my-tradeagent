package com.ohmytradeagent.orchestrator.workflows;

import java.util.regex.Pattern;

/**
 * Shared {@code broker_target} whitelist used by {@link ExecActivitiesFactory} (workflow-side
 * routing) and {@code ReconciliationScheduleBootstrapper} (bootstrap-side schedule creation). Lives
 * in {@code workflows} with {@code public} visibility so the {@code bootstrap} package can consume
 * it without reaching into the factory's package-private API.
 *
 * <p>Admits the legacy {@code paper} / {@code live} values (so audit-record deserialization still
 * works) plus the Phase 2c.2 {@code <provider>-<env>} shape (e.g. {@code alpaca-paper}). Passing
 * the whitelist does not guarantee a worker queue exists — see {@link
 * ExecActivitiesFactory#LEGACY_BARE_TARGETS}.
 */
public final class BrokerTargetValidator {

  /**
   * One of: {@code paper}, {@code live}, or {@code <provider>-<env>} where provider is lowercase
   * letters and env is {@code paper} or {@code live}. Matches the contract schema's broker_target
   * enum exactly.
   */
  public static final Pattern VALID_TARGET = Pattern.compile("^(paper|live|[a-z]+-(paper|live))$");

  private BrokerTargetValidator() {}

  /** Returns true iff {@code brokerTarget} is non-null and matches {@link #VALID_TARGET}. */
  public static boolean isValid(String brokerTarget) {
    return brokerTarget != null && VALID_TARGET.matcher(brokerTarget).matches();
  }
}
