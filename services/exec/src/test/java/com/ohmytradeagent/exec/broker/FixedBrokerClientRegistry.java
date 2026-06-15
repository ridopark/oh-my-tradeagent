package com.ohmytradeagent.exec.broker;

/**
 * Test-only {@link BrokerClientRegistry} that returns a single fixed {@link OptionsBroker} for
 * EVERY key. Lets the existing single-broker activity / poller tests run against the
 * registry-injected consumers unchanged (the env-fallback registry is itself a
 * one-broker-per-tenant registry, so a fixed registry is a faithful stand-in for the
 * behavior-preservation assertions).
 */
public final class FixedBrokerClientRegistry implements BrokerClientRegistry {

  private final OptionsBroker broker;

  public FixedBrokerClientRegistry(OptionsBroker broker) {
    this.broker = broker;
  }

  @Override
  public OptionsBroker brokerFor(String tenantId, String provider, String declaredAccountId) {
    return broker;
  }
}
