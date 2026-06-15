package com.ohmytradeagent.exec.broker;

/**
 * Resolved broker credentials + endpoints for one {@code (tenant, provider)} key. Produced by a
 * {@link BrokerCredentialSource} and consumed by a {@link BrokerClientRegistry} to build a
 * per-account broker client.
 *
 * <p>P4-a note: under the env-fallback source every key resolves to the SAME values (today's single
 * env cred set), so the registry path is byte-identical to the pre-P4-a single broker. P4-b adds
 * the per-tenant source that returns distinct values per tenant.
 *
 * <ul>
 *   <li>{@code apiKeyId} / {@code apiSecretKey} — the Alpaca {@code APCA-API-KEY-ID} / {@code
 *       APCA-API-SECRET-KEY} headers.
 *   <li>{@code baseUrl} — the REST base URL (paper vs live host).
 *   <li>{@code wsUrl} — the fill-listener trade-updates WS URL (carried for the mode-coherence
 *       check; the WS code itself is untouched this phase).
 *   <li>{@code expectedAccountId} — the operator-declared brokerage account the keys must
 *       authenticate; blank disables the P2 account-identity assertion (paper / back-compat).
 * </ul>
 */
public record BrokerCredentials(
    String apiKeyId, String apiSecretKey, String baseUrl, String wsUrl, String expectedAccountId) {}
