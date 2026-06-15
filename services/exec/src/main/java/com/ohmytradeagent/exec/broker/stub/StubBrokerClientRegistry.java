package com.ohmytradeagent.exec.broker.stub;

import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stub {@link BrokerClientRegistry}: returns the single in-memory {@link StubBroker} for EVERY key,
 * building no RestClient. Selected by {@code broker.impl=stub} (and the absent default) — mirrors
 * {@link StubBroker}'s condition so exactly one {@link BrokerClientRegistry} bean exists per
 * profile.
 */
@Component
@ConditionalOnProperty(name = "broker.impl", havingValue = "stub", matchIfMissing = true)
public class StubBrokerClientRegistry implements BrokerClientRegistry {

  private final StubBroker stubBroker;

  public StubBrokerClientRegistry(StubBroker stubBroker) {
    this.stubBroker = stubBroker;
  }

  @Override
  public OptionsBroker brokerFor(String tenantId, String provider) {
    return stubBroker;
  }
}
