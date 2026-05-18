package com.ohmytradeagent.marketdata.provider.alpaca;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Boot-bound Alpaca market-data config. {@code apiKeyId} / {@code apiSecretKey} are required when
 * {@code market-data.provider=alpaca}; {@link AlpacaMarketDataConfig#alpacaMarketDataRestClient}
 * fails fast on missing values so a misconfigured paper deployment cannot silently boot and 401 on
 * every quote.
 *
 * @param dataBaseUrl base URL for REST snapshots (e.g. {@code https://data.alpaca.markets})
 * @param dataWsUrl WebSocket URL for the options stream (e.g. {@code
 *     wss://stream.data.alpaca.markets/v1beta1/indicative})
 */
@ConfigurationProperties(prefix = "market-data.alpaca")
public record AlpacaMarketDataProperties(
    String dataBaseUrl, String dataWsUrl, String apiKeyId, String apiSecretKey) {}
