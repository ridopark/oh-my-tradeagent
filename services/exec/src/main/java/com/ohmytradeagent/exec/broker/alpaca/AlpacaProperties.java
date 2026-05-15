package com.ohmytradeagent.exec.broker.alpaca;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Boot-bound Alpaca config. {@code apiKeyId} / {@code apiSecretKey} are required when {@code
 * broker.impl=alpaca-paper}; {@link AlpacaConfig#alpacaRestClient(AlpacaProperties,
 * org.springframework.web.client.RestClient.Builder)} fails fast at startup if either is blank.
 */
@ConfigurationProperties(prefix = "alpaca")
public record AlpacaProperties(String baseUrl, String apiKeyId, String apiSecretKey) {}
