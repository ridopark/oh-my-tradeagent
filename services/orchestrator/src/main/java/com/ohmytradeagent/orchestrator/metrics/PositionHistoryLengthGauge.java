package com.ohmytradeagent.orchestrator.metrics;

import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.alert.WebhookClient;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionMetadata;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Issue #752 Phase 1: polls {@code DescribeWorkflowExecution} for every RUNNING {@code
 * PositionWorkflow} and publishes its {@code history_length} as the same Prometheus gauge {@link
 * KillSwitchHistoryLengthGauge} uses ({@code temporal_workflow_history_length}, here tagged {@code
 * workflow_type="PositionWorkflow"} plus {@code tenant_id}/{@code strategy_id}/{@code
 * contract_symbol}), and pages Discord once per workflow when the length crosses {@link
 * #warnHistoryLength}.
 *
 * <p>Why this exists: {@code PositionWorkflowImpl} has no continue-as-new, so an armed LEAP trail
 * (~200 history events per RTH day through the premium-tick signal quartet) crosses Temporal's
 * 10,000-event watermark in about two months and the workflow — a live real-money position — fails
 * terminally. This gauge converts that silent fuse into a paged one with ~20 trading days of
 * runway, and its per-position reading is the hard pre-deploy gate for the Phase 2 roll (a position
 * already above the watermark at deploy time would diverge on replay).
 *
 * <p>The read must be out-of-band, exactly like the kill-switch gauge: {@code Workflow.getInfo()}
 * is workflow-only API, and a metric activity inside the workflow would grow the very history this
 * bounds.
 *
 * <p>Differences from the kill-switch template, both deliberate: the running set comes from the
 * Visibility query {@code WorkflowType = 'PositionWorkflow' AND ExecutionStatus = 'Running'} (the
 * same string #784's recovery sweep uses) rather than a static tenants dir, so gauges are
 * REGISTERED as positions open and DEREGISTERED as they close — without the removal, the meter
 * registry leaks one gauge per closed position for the pod's life. And the cadence is 5 minutes,
 * not 60s: at ~200 events/day nothing here moves fast, and the estate has one Temporal frontend.
 *
 * <p>Paging is per-workflow-id, once per pod lifetime, via the per-tenant webhook route (global
 * fallback). The page is advisory — nothing here touches the workflow.
 */
@Component
@Profile("!test")
public class PositionHistoryLengthGauge {

  /** Reported value when {@code DescribeWorkflowExecution} returns {@code NOT_FOUND}. */
  static final long NOT_FOUND_VALUE = -1L;

  /**
   * Page when a running position's history length crosses this. 6,000 (not 9,000): at ~200
   * events/RTH day that leaves ~20 trading days to plan a supervised intervention instead of
   * reacting to one. Package-private and non-final so tests can lower it via reflection — same KISS
   * rationale as {@code KillSwitchWorkflowImpl.historyLengthWatermark}: no config knob with zero
   * operational use.
   */
  static long warnHistoryLength = 6_000L;

  private static final Logger log = LoggerFactory.getLogger(PositionHistoryLengthGauge.class);
  private static final String METRIC_NAME = "temporal_workflow_history_length";
  private static final String WORKFLOW_TYPE = "PositionWorkflow";

  /** Same query string as {@code PremiumSubscriptionRecovery} (#784) — keep them in lockstep. */
  static final String LIST_QUERY =
      "WorkflowType = 'PositionWorkflow' AND ExecutionStatus = 'Running'";

  private final WorkflowClient workflowClient;
  private final MeterRegistry meterRegistry;
  private final WebhookClient webhookClient;

  private record Entry(AtomicLong value, Meter.Id meterId) {}

  private final Map<String, Entry> registered = new ConcurrentHashMap<>();
  private final Set<String> paged = ConcurrentHashMap.newKeySet();

  public PositionHistoryLengthGauge(
      WorkflowClient workflowClient, MeterRegistry meterRegistry, WebhookClient webhookClient) {
    this.workflowClient = workflowClient;
    this.meterRegistry = meterRegistry;
    this.webhookClient = webhookClient;
  }

  @Scheduled(fixedDelay = 300_000L, initialDelay = 60_000L)
  public void poll() {
    List<String> workflowIds;
    try (Stream<WorkflowExecutionMetadata> stream = workflowClient.listExecutions(LIST_QUERY)) {
      workflowIds = new ArrayList<>(stream.map(m -> m.getExecution().getWorkflowId()).toList());
    } catch (RuntimeException e) {
      log.warn("listExecutions failed; keeping previous gauge values", e);
      return;
    }

    String namespace = workflowClient.getOptions().getNamespace();
    Map<String, Long> lengths = new LinkedHashMap<>();
    for (String wfId : workflowIds) {
      lengths.put(wfId, describeHistoryLength(namespace, wfId));
    }
    update(lengths);
  }

  /**
   * Register/refresh a gauge per running position, page once per position past the warn level, and
   * deregister positions that closed. Package-private seam: everything below the Temporal fetch is
   * exercised directly by the unit test (constructing {@code WorkflowExecutionMetadata} outside a
   * real client is not practical).
   */
  void update(Map<String, Long> historyLengthsByWorkflowId) {
    for (Map.Entry<String, Long> e : historyLengthsByWorkflowId.entrySet()) {
      String wfId = e.getKey();
      long historyLength = e.getValue();
      Entry entry = registered.computeIfAbsent(wfId, this::registerGauge);
      if (entry == null) {
        continue; // malformed id — refused, logged once inside registerGauge
      }
      entry.value().set(historyLength);
      if (historyLength >= warnHistoryLength && paged.add(wfId)) {
        page(wfId, historyLength);
      }
    }

    // Deregister positions that are no longer running, or the registry leaks a gauge per closed
    // position for the pod's life.
    Set<String> current = historyLengthsByWorkflowId.keySet();
    registered
        .entrySet()
        .removeIf(
            e -> {
              if (current.contains(e.getKey())) {
                return false;
              }
              meterRegistry.remove(e.getValue().meterId());
              paged.remove(e.getKey());
              return true;
            });
  }

  private long describeHistoryLength(String namespace, String wfId) {
    DescribeWorkflowExecutionRequest req =
        DescribeWorkflowExecutionRequest.newBuilder()
            .setNamespace(namespace)
            .setExecution(WorkflowExecution.newBuilder().setWorkflowId(wfId).build())
            .build();
    try {
      DescribeWorkflowExecutionResponse resp =
          workflowClient.getWorkflowServiceStubs().blockingStub().describeWorkflowExecution(req);
      return resp.getWorkflowExecutionInfo().getHistoryLength();
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        return NOT_FOUND_VALUE;
      }
      log.warn("describeWorkflowExecution failed wf_id={} status={}", wfId, e.getStatus(), e);
      return NOT_FOUND_VALUE;
    } catch (RuntimeException e) {
      log.warn("describeWorkflowExecution failed wf_id={}", wfId, e);
      return NOT_FOUND_VALUE;
    }
  }

  /** Returns {@code null} (and logs) for an id that does not parse as a position id. */
  private Entry registerGauge(String wfId) {
    String tenantId = segment(wfId, "t-");
    String strategyId = segment(wfId, "/s-");
    String occ = WorkflowIds.occFromPosition(wfId);
    if (tenantId == null || strategyId == null || occ == null) {
      log.warn("unparseable PositionWorkflow id, not gauging: {}", wfId);
      return null;
    }
    AtomicLong value = new AtomicLong(NOT_FOUND_VALUE);
    Gauge gauge =
        Gauge.builder(METRIC_NAME, value, AtomicLong::doubleValue)
            .tag("workflow_type", WORKFLOW_TYPE)
            .tag("tenant_id", tenantId)
            .tag("strategy_id", strategyId)
            .tag("contract_symbol", occ)
            .description(
                "History event count for the named workflow run (DescribeWorkflowExecution).")
            .register(meterRegistry);
    log.info("registered {} gauge wf_id={}", METRIC_NAME, wfId);
    return new Entry(value, gauge.getId());
  }

  /**
   * The id segment following {@code marker} up to the next {@code '/'}, or {@code null} if absent
   * or empty. Position ids are {@code t-<tenant>/s-<strategy>/pos/<occ>/<entrySignalId>} (see
   * {@link WorkflowIds#position}); tenant and strategy ids never contain {@code '/'}.
   */
  private static String segment(String wfId, String marker) {
    int start = marker.startsWith("/") ? wfId.indexOf(marker) : (wfId.startsWith(marker) ? 0 : -1);
    if (start < 0) {
      return null;
    }
    start += marker.length();
    int end = wfId.indexOf('/', start);
    if (end <= start) {
      return null;
    }
    return wfId.substring(start, end);
  }

  private void page(String wfId, long historyLength) {
    String tenantId = segment(wfId, "t-");
    String occ = WorkflowIds.occFromPosition(wfId);
    String msg =
        ":warning: **PositionWorkflow history at "
            + historyLength
            + " events** (warn "
            + warnHistoryLength
            + ", terminal 10,000) — "
            + occ
            + " on "
            + tenantId
            + ". ~20 trading days of runway at trail-tick rate. Plan a supervised roll or"
            + " terminate+re-adopt BEFORE the watermark (issue #752). wf_id: `"
            + wfId
            + "`";
    try {
      webhookClient.post(tenantId, msg);
    } catch (RuntimeException e) {
      // Paging must never break the scrape; the gauge itself is the durable signal.
      log.warn("history-length page failed wf_id={}", wfId, e);
    }
  }
}
