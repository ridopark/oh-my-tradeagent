package com.ohmytradeagent.apigateway.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the per-broker_target exec base URLs ({@code exec.targets.*}, see {@link
 * ExecTargetProperties}). Gated on every flag of {@link ExecClientConfig} (the credential-write
 * forward and the arm-guard / tenant-delete exec reads) PLUS {@code
 * live.fill-listener-status.enabled}, whose read-only status route routes on the same map but needs
 * none of the secret-bearing {@code execRestClient}. Holds only in-cluster service URLs.
 */
@Configuration
@ConditionalOnExpression(
    "${broker.credentials.write.enabled:false} or ${operator.credential-write.enabled:false}"
        + " or ${operator.strategy-enable.enabled:false}"
        + " or ${strategy.config.write.enabled:false}"
        + " or ${operator.tenant-delete.enabled:false}"
        + " or ${live.fill-listener-status.enabled:false}")
public class ExecTargetConfig {

  @Bean
  @ConfigurationProperties("exec")
  public ExecTargetProperties execTargetProperties() {
    return new ExecTargetProperties();
  }
}
