package com.ohmytradeagent.tdbff.portfolio;

import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.PositionSnapshotRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fetches broker-held positions WITH live marks by starting the short-lived {@code
 * PositionSnapshotWorkflow} on the orchestrator task queue and reading its result — the same
 * synchronous start-and-getResult pattern {@link AccountEquityClient} uses for account equity. The
 * BFF is a Temporal <em>client</em>, so it cannot dispatch the broker-positions Activity directly
 * (activities only run inside workflows); the workflow, hosted on the orchestrator worker,
 * dispatches {@code ReconciliationExecActivity.brokerListOpenPositions} to {@code broker-<target>}.
 * No broker credentials live in the BFF.
 *
 * <p>The marks are broker-ACCOUNT-level — shared by every tenant routing to a given {@code
 * broker_target} — so the P&amp;L shown is the position's actual broker P&amp;L (NOT a per-tenant
 * computation), and never a risk-gate input.
 *
 * <p>Read-only and FAIL-SOFT: on any timeout/error it returns an empty map (no marks) rather than
 * throwing, so a degraded snapshot drops the mark columns on the portfolio page instead of failing
 * the whole read — exactly like {@link AccountEquityClient}'s {@code BrokerAccount(null, null)}
 * degrade.
 */
@Component
public class BrokerPositionsClient {

  private static final Logger log = LoggerFactory.getLogger(BrokerPositionsClient.class);
  private static final String WORKFLOW_TYPE = "PositionSnapshotWorkflow";
  // Client-side wait bound for the blocking getResult, mirroring AccountEquityClient: kept short
  // (not the workflow's full 60s scheduleToCloseTimeout) so the portfolio PAGE stays responsive —
  // a healthy positions snapshot returns in well under a second; if it doesn't we degrade to no
  // marks rather than make the user wait. The workflow's own 60s timeout still bounds it
  // server-side.
  private static final long RESULT_TIMEOUT_SECONDS = 8;

  // The workflow returns List<BrokerPosition>; an untyped stub (the BFF can't see the workflow
  // interface, which lives in the orchestrator module) needs the FULL generic Type so Temporal's
  // DataConverter deserializes each array element as a BrokerPosition rather than a raw map. Built
  // once as an explicit ParameterizedType to avoid a transitive Guava TypeToken dependency.
  private static final Type POSITIONS_RESULT_TYPE = listOf(BrokerPosition.class);

  /**
   * Live marks for one broker-held position. Any field may be null when the broker omits it. {@code
   * brokerQty} (#832) is the broker POSITION's total contract count — the divisor that lets the
   * caller prorate the account-level intraday figure onto sibling workflow rows sharing this OCC
   * (exact for intraday: {@code current − lastday} is identical per contract regardless of entry
   * basis).
   */
  public record PositionMarks(
      BigDecimal currentPrice,
      BigDecimal unrealizedPl,
      BigDecimal unrealizedIntradayPl,
      Long brokerQty) {}

  private final WorkflowClient client;
  private final String orchestratorTaskQueue;

  public BrokerPositionsClient(
      WorkflowClient client,
      @Value("${temporal.orchestrator-task-queue:orchestrator-core}")
          String orchestratorTaskQueue) {
    this.client = client;
    this.orchestratorTaskQueue = orchestratorTaskQueue;
  }

  /**
   * Live marks for the account behind {@code brokerTarget}, keyed by the NORMALIZED-COMPACT OCC
   * (whitespace stripped) so the caller can join them onto its tracked positions regardless of
   * padded-vs-compact OCC form. Never {@code null}: on any timeout/error/degrade it returns an
   * empty map (a read-only view degrades gracefully rather than failing the whole portfolio page).
   * The {@code tenant_id}/{@code strategy_id} are forward-compat filter hooks the single-account
   * broker adapter ignores today (marks are account-level).
   */
  public Map<String, PositionMarks> marksFor(
      String brokerTarget, String tenantId, String strategyId) {
    PositionSnapshotRequest request = new PositionSnapshotRequest();
    request.setSchemaVersion(1L);
    request.setBrokerTarget(PositionSnapshotRequest.BrokerTarget.fromValue(brokerTarget));
    request.setTenantId(tenantId);
    request.setStrategyId(strategyId);
    request.setCorrelationId("dashboard-" + UUID.randomUUID());

    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setTaskQueue(orchestratorTaskQueue)
            .setWorkflowId("position-snapshot/" + brokerTarget + "/" + UUID.randomUUID())
            .build();
    WorkflowStub stub = client.newUntypedWorkflowStub(WORKFLOW_TYPE, opts);
    try {
      stub.start(request);
      // Bounded wait (RESULT_TIMEOUT_SECONDS) so an unreachable Temporal service / down task queue
      // cannot pin a Spring MVC request thread indefinitely; on timeout we degrade to no marks like
      // any other snapshot-unavailable case.
      @SuppressWarnings("unchecked")
      List<BrokerPosition> positions =
          stub.getResult(
              RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS, List.class, POSITIONS_RESULT_TYPE);
      return index(positions);
    } catch (TimeoutException e) {
      // We stopped waiting, but the workflow is still running. Cancel it so it doesn't linger as an
      // orphan — holding an orchestrator worker slot and re-hitting the broker positions endpoint —
      // long after this request already degraded to no marks.
      log.warn(
          "PositionSnapshotWorkflow timed out broker_target={}; cancelling orphan", brokerTarget);
      try {
        stub.cancel();
      } catch (RuntimeException cancelErr) {
        log.warn(
            "PositionSnapshotWorkflow cancel failed broker_target={} err={}",
            brokerTarget,
            cancelErr.getMessage());
      }
      return Map.of();
    } catch (RuntimeException e) {
      log.warn(
          "PositionSnapshotWorkflow failed broker_target={} err={}", brokerTarget, e.getMessage());
      return Map.of();
    }
  }

  /**
   * Index a position list by normalized-compact OCC (whitespace stripped). A position with no marks
   * at all is still indexed (its {@link PositionMarks} fields are simply null) so the join is
   * driven purely by OCC presence.
   */
  private static Map<String, PositionMarks> index(List<BrokerPosition> positions) {
    if (positions == null || positions.isEmpty()) {
      return Map.of();
    }
    Map<String, PositionMarks> out = new LinkedHashMap<>();
    for (BrokerPosition p : positions) {
      String occ = compactOcc(p.getOptionSymbol());
      if (occ == null) {
        continue;
      }
      out.put(
          occ,
          new PositionMarks(
              p.getCurrentPrice(), p.getUnrealizedPl(), p.getUnrealizedIntradayPl(), p.getQty()));
    }
    return out;
  }

  /** Strip ALL whitespace so a padded OCC and a compact OCC for the same contract collide. */
  static String compactOcc(String occ) {
    if (occ == null) {
      return null;
    }
    String compact = occ.replaceAll("\\s+", "");
    return compact.isEmpty() ? null : compact;
  }

  /**
   * A {@code List<element>} {@link Type} for Temporal's typed {@code getResult} — a minimal
   * explicit {@link ParameterizedType} so the result is deserialized element-by-element as {@code
   * element} (no transitive Guava {@code TypeToken} dependency).
   */
  private static Type listOf(Type element) {
    return new ParameterizedType() {
      @Override
      public Type[] getActualTypeArguments() {
        return new Type[] {element};
      }

      @Override
      public Type getRawType() {
        return List.class;
      }

      @Override
      public Type getOwnerType() {
        return null;
      }
    };
  }
}
