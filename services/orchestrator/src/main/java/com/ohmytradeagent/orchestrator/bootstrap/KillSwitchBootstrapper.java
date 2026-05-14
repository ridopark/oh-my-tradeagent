package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.contract.KillSwitchWorkflowInput;
import com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * On Spring start, scans the tenants directory and ensures one {@link KillSwitchWorkflow} is
 * running per {@code (tenant, strategy)}. Uses {@code REJECT_DUPLICATE} reuse policy so warm boots
 * don't disturb an already-running workflow — the {@link WorkflowExecutionAlreadyStarted} exception
 * is logged and ignored.
 */
@Component
@Profile("!test")
public class KillSwitchBootstrapper implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(KillSwitchBootstrapper.class);

  static final String KILLSWITCH_TASK_QUEUE = "orchestrator-core";

  private final WorkflowClient workflowClient;
  private final Path tenantsDir;

  public KillSwitchBootstrapper(
      WorkflowClient workflowClient,
      @Value("${orchestrator.tenants-dir:tenants}") String tenantsDir) {
    this.workflowClient = workflowClient;
    this.tenantsDir = Path.of(tenantsDir);
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!Files.exists(tenantsDir)) {
      log.warn("tenants dir {} not found; skipping KillSwitchWorkflow bootstrap", tenantsDir);
      return;
    }
    List<Path> tenantDirs = listSubdirs(tenantsDir);
    for (Path tenantDir : tenantDirs) {
      String tenantId = tenantDir.getFileName().toString();
      Path strategiesDir = tenantDir.resolve("strategies");
      if (!Files.exists(strategiesDir)) {
        continue;
      }
      List<Path> strategyFiles = listYamlFiles(strategiesDir);
      for (Path file : strategyFiles) {
        String fileName = file.getFileName().toString();
        String strategyId = fileName.substring(0, fileName.length() - ".yaml".length());
        startKillSwitch(tenantId, strategyId);
      }
    }
  }

  private void startKillSwitch(String tenantId, String strategyId) {
    String wfId = "t-" + tenantId + "/s-" + strategyId + "/killswitch";
    Map<String, Object> sa = new HashMap<>();
    sa.put("TenantStrategy", "t-" + tenantId + "/s-" + strategyId);

    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setWorkflowId(wfId)
            .setTaskQueue(KILLSWITCH_TASK_QUEUE)
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setSearchAttributes(sa)
            .build();

    KillSwitchWorkflow stub = workflowClient.newWorkflowStub(KillSwitchWorkflow.class, opts);
    KillSwitchWorkflowInput input = new KillSwitchWorkflowInput();
    input.setSchemaVersion(1L);
    input.setTenantId(tenantId);
    input.setStrategyId(strategyId);

    try {
      WorkflowClient.start(stub::run, input);
      log.info("started KillSwitchWorkflow wf_id={}", wfId);
    } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
      log.info("KillSwitchWorkflow wf_id={} already running (warm boot)", wfId);
    } catch (RuntimeException e) {
      log.error("failed to start KillSwitchWorkflow wf_id={}", wfId, e);
    }
  }

  private static List<Path> listSubdirs(Path dir) {
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(Files::isDirectory).toList();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to list " + dir, e);
    }
  }

  private static List<Path> listYamlFiles(Path dir) {
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(p -> p.toString().endsWith(".yaml")).toList();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to list " + dir, e);
    }
  }
}
