package com.ohmytradeagent.exec.broker.alpaca;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Boot-bound Alpaca config. {@code apiKeyId} / {@code apiSecretKey} are required when {@code
 * broker.impl=alpaca-*}; {@link AlpacaModeCoherence#assertCredentialsPresent(String, String)} fails
 * fast (inside the {@code BrokerClientRegistry} build) if either is blank.
 */
@ConfigurationProperties(prefix = "alpaca")
public record AlpacaProperties(String baseUrl, String apiKeyId, String apiSecretKey) {}
