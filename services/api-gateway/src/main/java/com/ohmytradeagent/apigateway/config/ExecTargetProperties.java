package com.ohmytradeagent.apigateway.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-broker_target exec base URLs for the credential-WRITE forward. The shared {@code
 * execRestClient} has a single {@code exec.base-url} (the paper pod), so forwarding EVERY
 * credential write there sends a {@code -live} tenant's real-money keys to the paper pod's DB. This
 * map lets {@link com.ohmytradeagent.apigateway.web.BrokerCredentialForwardService} route each
 * write to the exec pod that owns the tenant's {@code broker_target}, issuing the POST to an
 * ABSOLUTE URI.
 *
 * <p>Bound from {@code exec.targets.*} (e.g. {@code exec.targets.alpaca-live}). NO fallback: a
 * broker_target absent from this map fails closed (no forward) — a {@code -live} target must NEVER
 * fall back to {@code exec.base-url} (the paper pod). Holds only in-cluster service URLs — NO
 * secret material.
 */
public class ExecTargetProperties {

  private Map<String, String> targets = new LinkedHashMap<>();

  public Map<String, String> getTargets() {
    return targets;
  }

  public void setTargets(Map<String, String> targets) {
    this.targets = targets;
  }
}
