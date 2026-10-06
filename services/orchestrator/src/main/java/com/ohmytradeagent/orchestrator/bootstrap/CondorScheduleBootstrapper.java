package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
import com.ohmytradeagent.orchestrator.workflows.CondorSessionWorkflow;
import com.ohmytradeagent.orchestrator.workflows.CondorSessionWorkflowInput;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleCalendarSpec;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleListDescription;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.ScheduleRange;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gated-condor Phase 4: keeps one {@link CondorSessionWorkflow} Schedule per (tenant, strategy)
 * whose config is condor-enabled — {@code condor_entry_et} set, {@code enabled != false}, and a
 * {@code <provider>-paper} broker target — firing Monday-Friday at {@code condor_entry_et} −
 * {@value #LEAD_MINUTES} min America/New_York. Holidays/half-days/FOMC are skipped by the session
 * itself.
 *
 * <p>Mirrors {@link ReconciliationScheduleBootstrapper}: schedule ids encode the fire time ({@code
 * condor-v1-t-<tenant>-s-<strategy>-<HHmm>}), so an entry-time change creates the new schedule and
 * reaps the old one; a strategy that stops being condor-enabled has its schedules reaped. Runs at
 * startup and on the tenant-reconcile fixed delay, so a DB-only config change is picked up without
 * a restart. Not workflow code — no determinism constraints.
 */
@Component
@Profile("!test")
public class CondorScheduleBootstrapper implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(CondorScheduleBootstrapper.class);

  static final int LEAD_MINUTES = 10;
  static final String TIME_ZONE = "America/New_York";

  private final WorkflowClient workflowClient;
  private final WorkflowServiceStubs serviceStubs;
  private final StrategyRegistry strategyRegistry;

  public CondorScheduleBootstrapper(
      WorkflowClient workflowClient,
      WorkflowServiceStubs serviceStubs,
      StrategyRegistry strategyRegistry) {
    this.workflowClient = workflowClient;
    this.serviceStubs = serviceStubs;
    this.strategyRegistry = strategyRegistry;
  }

  @Override
  public void run(ApplicationArguments args) {
    tick();
  }

  @Scheduled(
      fixedDelayString = "${orchestrator.tenant-reconcile.fixed-delay-ms:60000}",
      initialDelayString = "${orchestrator.tenant-reconcile.fixed-delay-ms:60000}")
  public void tick() {
    try {
      runWith(
          ReconciliationScheduleBootstrapper.scheduleClientForNamespace(
              serviceStubs, workflowClient.getOptions().getNamespace()));
    } catch (RuntimeException e) {
      log.error("condor schedule pass failed; retrying next tick", e);
    }
  }

  /** Package-private for tests: one pass against a (mock) {@link ScheduleClient}. */
  void runWith(ScheduleClient scheduleClient) {
    List<ScheduleListDescription> existing;
    try (Stream<ScheduleListDescription> listed = scheduleClient.listSchedules()) {
      existing = listed.collect(Collectors.toUnmodifiableList());
    }
    for (TenantStrategy ts : strategyRegistry.list()) {
      LocalTime fire;
      try {
        fire = fireTime(strategyRegistry.get(ts.tenantId(), ts.strategyId()));
      } catch (RuntimeException e) {
        // Fail-closed on an unreadable config: leave whatever exists untouched this pass.
        log.error("condor schedule: config read failed tenant={} strategy={}", ts, e);
        continue;
      }
      String desired = fire == null ? null : scheduleId(ts, fire);
      String prefix = schedulePrefix(ts.tenantId(), ts.strategyId());
      for (ScheduleListDescription d : existing) {
        String id = d.getScheduleId();
        if (id.startsWith(prefix) && !id.equals(desired)) {
          try {
            scheduleClient.getHandle(id).delete();
            log.info("condor schedule: reaped {}", id);
          } catch (RuntimeException e) {
            log.warn("condor schedule: could not reap {}", id, e);
          }
        }
      }
      if (desired != null) {
        ensure(scheduleClient, ts, desired, fire);
      }
    }
  }

  static String scheduleId(TenantStrategy ts, LocalTime fire) {
    return schedulePrefix(ts.tenantId(), ts.strategyId())
        + String.format("%02d%02d", fire.getHour(), fire.getMinute());
  }

  /** {@code condor_entry_et − LEAD_MINUTES}, or null when the strategy is not condor-enabled. */
  static LocalTime fireTime(StrategyConfig cfg) {
    if (cfg == null
        || cfg.getCondorEntryEt() == null
        || Boolean.FALSE.equals(cfg.getEnabled())
        || cfg.getBrokerTarget() == null
        || !cfg.getBrokerTarget().value().endsWith("-paper")) {
      return null;
    }
    try {
      return LocalTime.parse(cfg.getCondorEntryEt()).minusMinutes(LEAD_MINUTES);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  static String schedulePrefix(String tenantId, String strategyId) {
    return "condor-v1-t-" + tenantId + "-s-" + strategyId + "-";
  }

  private void ensure(
      ScheduleClient scheduleClient, TenantStrategy ts, String scheduleId, LocalTime fire) {
    Map<String, Object> sa = new LinkedHashMap<>();
    sa.put("TenantStrategy", WorkflowIds.tenantStrategy(ts.tenantId(), ts.strategyId()));
    ScheduleActionStartWorkflow action =
        ScheduleActionStartWorkflow.newBuilder()
            .setWorkflowType(CondorSessionWorkflow.class)
            .setArguments(new CondorSessionWorkflowInput(ts.tenantId(), ts.strategyId()))
            .setOptions(
                WorkflowOptions.newBuilder()
                    .setWorkflowId(WorkflowIds.condorSessionPrefix(ts.tenantId(), ts.strategyId()))
                    .setTaskQueue(ReconciliationScheduleBootstrapper.CORE_TASK_QUEUE)
                    .setSearchAttributes(sa)
                    .build())
            .build();
    ScheduleSpec spec =
        ScheduleSpec.newBuilder()
            .setCalendars(
                List.of(
                    ScheduleCalendarSpec.newBuilder()
                        .setSeconds(List.of(new ScheduleRange(0)))
                        .setMinutes(List.of(new ScheduleRange(fire.getMinute())))
                        .setHour(List.of(new ScheduleRange(fire.getHour())))
                        .setDayOfWeek(List.of(new ScheduleRange(1, 5)))
                        .build()))
            .setTimeZoneName(TIME_ZONE)
            .build();
    try {
      scheduleClient.createSchedule(
          scheduleId,
          Schedule.newBuilder().setAction(action).setSpec(spec).build(),
          ScheduleOptions.newBuilder().build());
      log.info("condor schedule: created {} ({} {} Mon-Fri)", scheduleId, fire, TIME_ZONE);
    } catch (ScheduleAlreadyRunningException already) {
      // benign: warm boot / repeat tick
    } catch (RuntimeException e) {
      log.error("condor schedule: failed to create {}", scheduleId, e);
    }
  }
}
