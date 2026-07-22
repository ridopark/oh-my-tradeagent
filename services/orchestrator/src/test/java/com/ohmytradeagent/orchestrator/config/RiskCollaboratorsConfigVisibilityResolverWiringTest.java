package com.ohmytradeagent.orchestrator.config;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.orchestrator.activities.PortfolioSnapshot;
import com.ohmytradeagent.orchestrator.activities.TenantStrategies;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.client.WorkflowClient;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Wiring guard for the notional-cap fail-open follow-up to PR #604: {@code
 * RiskCollaboratorsConfig.visibilityPortfolioSnapshot} must build the snapshot with the INJECTED
 * shared {@link TenantStrategies} bean (DB-backed in {@code strategy.config.source=db}) rather than
 * hard-constructing a tree-scanning resolver. Proves the injected resolver is consulted AND that a
 * MULTI-strategy tenant's full set flows into {@code sum_open_notional} — so a DB-onboarded tenant
 * absent from the ConfigMap tree can no longer silently undercount its open notional (fail-OPEN).
 */
class RiskCollaboratorsConfigVisibilityResolverWiringTest {

  @Test
  void visibilityPortfolioSnapshot_usesInjectedResolver_andQueriesEveryTenantStrategy() {
    WorkflowClient client = mock(WorkflowClient.class);
    // Fresh stream per call — a Java Stream is single-use, and the snapshot queries once per
    // resolved strategy.
    when(client.listExecutions(anyString())).thenAnswer(inv -> Stream.of());

    // A multi-strategy tenant: the resolver returns TWO strategies. The pre-fix hard-coded scanner
    // would resolve a tree-absent tenant to just the requesting strategy (one query); the injected
    // resolver must widen the basis to both.
    TenantStrategies tenantStrategies = mock(TenantStrategies.class);
    when(tenantStrategies.strategyIdsForTenant("prod-multi"))
        .thenReturn(List.of("copytrade-v1", "watchlist-trigger-v1"));

    PortfolioSnapshot snapshot =
        new RiskCollaboratorsConfig()
            .visibilityPortfolioSnapshot(client, new SimpleMeterRegistry(), tenantStrategies);

    snapshot.openPositions("prod-multi", "copytrade-v1");

    // The injected resolver was consulted (not a hard-constructed scanner) ...
    verify(tenantStrategies).strategyIdsForTenant("prod-multi");
    // ... and BOTH of the tenant's strategies were queried — no undercount to the requesting one.
    verify(client, times(2)).listExecutions(anyString());
  }
}
