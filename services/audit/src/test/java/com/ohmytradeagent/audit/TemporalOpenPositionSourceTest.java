package com.ohmytradeagent.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * #854 review: every other test stubs {@link OpenPositionSource} at the interface level, leaving
 * the part that can actually regress untested — the workflow-id prefix match, the correlation-id
 * recovery, and the fail-closed wrapping. An accidental change to {@code
 * WorkflowIds.tenantStrategy}'s separator would have gone unnoticed by CI.
 *
 * <p>Drives the package-private seam rather than mocking Temporal's visibility stream, so the
 * assertions are about this class's logic and not about Temporal's API shape.
 */
class TemporalOpenPositionSourceTest {

  /** Real ids as they appear on the homelab, including the operator manual-entry shape. */
  private static final String PROD_INTC =
      "t-prod_real/s-copytrade-v1/pos/INTC  260925C00120000/"
          + "chat-messages-769797179992571914-1547246647782940833:0";

  private static final String PROD_MANUAL =
      "t-prod_real/s-copytrade-v1/pos/SMCI  261120C00050000/"
          + "manual:217cebd4-2207-4cfe-808f-1faa7d793a4a";

  private static final String OTHER_TENANT =
      "t-prod-kipark/s-copytrade-v1/pos/INTC  260925C00120000/"
          + "chat-messages-769797179992571914-1547246647782940833:0";

  private static final String WATCHLIST_LEG =
      "t-staging_paper/s-watchlist-trigger-v1/pos/NVDA  260918C00180000/wl/2026-09-10/NVDA/C";

  private final AtomicInteger fetches = new AtomicInteger();

  private TemporalOpenPositionSource sourceReturning(List<String> workflowIds) {
    return new TemporalOpenPositionSource(null) {
      @Override
      List<String> fetchRunningPositionWorkflowIds() {
        fetches.incrementAndGet();
        return workflowIds;
      }
    };
  }

  @Test
  void extractsCorrelationIdsForTheRequestedTenantAndStrategyOnly() {
    Set<String> open =
        sourceReturning(List.of(PROD_INTC, PROD_MANUAL, OTHER_TENANT, WATCHLIST_LEG))
            .openCorrelationIds("prod_real", "copytrade-v1");

    assertThat(open)
        .containsExactlyInAnyOrder(
            "chat-messages-769797179992571914-1547246647782940833:0",
            "manual:217cebd4-2207-4cfe-808f-1faa7d793a4a");
  }

  /**
   * prod_real and prod-kipark hold the SAME contract under the SAME entry signal id — a copytrade
   * signal fans out to every subscribing tenant. The prefix filter is the only thing keeping one
   * tenant's open position from marking another tenant's lifecycle as open.
   */
  @Test
  void doesNotLeakAnotherTenantsOpenPosition() {
    Set<String> open =
        sourceReturning(List.of(OTHER_TENANT)).openCorrelationIds("prod_real", "copytrade-v1");

    assertThat(open).isEmpty();
  }

  /** A watchlist leg's signal id contains slashes of its own; recovery must keep all of it. */
  @Test
  void recoversWatchlistSignalIdsThatContainSlashes() {
    Set<String> open =
        sourceReturning(List.of(WATCHLIST_LEG))
            .openCorrelationIds("staging_paper", "watchlist-trigger-v1");

    assertThat(open).containsExactly("wl/2026-09-10/NVDA/C");
  }

  @Test
  void ignoresWorkflowIdsThatAreNotPositions() {
    Set<String> open =
        sourceReturning(
                List.of(
                    "t-prod_real/s-copytrade-v1/killswitch",
                    "t-prod_real/account/killswitch",
                    "t-prod_real/s-copytrade-v1/sig/chat-messages-1:0"))
            .openCorrelationIds("prod_real", "copytrade-v1");

    assertThat(open).isEmpty();
  }

  /** The listing is cluster-wide, so asking per pair must not re-scan it per pair. */
  @Test
  void fetchesTheRunningSetOncePerProcess() {
    TemporalOpenPositionSource source = sourceReturning(List.of(PROD_INTC, OTHER_TENANT));

    source.openCorrelationIds("prod_real", "copytrade-v1");
    source.openCorrelationIds("prod-kipark", "copytrade-v1");
    source.openCorrelationIds("staging_paper", "watchlist-trigger-v1");

    assertThat(fetches.get()).isEqualTo(1);
  }

  /** Fail CLOSED: an unavailable lookup must refuse, never report "nothing is open". */
  @Test
  void propagatesLookupFailureRatherThanReturningEmpty() {
    TemporalOpenPositionSource source =
        new TemporalOpenPositionSource(null) {
          @Override
          List<String> fetchRunningPositionWorkflowIds() {
            throw new IllegalStateException("Temporal visibility unavailable");
          }
        };

    assertThatThrownBy(() -> source.openCorrelationIds("prod_real", "copytrade-v1"))
        .isInstanceOf(IllegalStateException.class);
  }
}
