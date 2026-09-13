package com.ohmytradeagent.audit;

import com.ohmytradeagent.contract.temporal.LenientDataConverter;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Temporal client for the audit verifier's open-position lookup. Read-only: it never starts or
 * signals a workflow, only lists visibility records. Mirrors api-gateway's client config, including
 * the #772 lenient converter so a since-removed schema field in a recorded history cannot wedge a
 * listing.
 */
@Configuration
public class TemporalClientConfig {

  @Value("${temporal.target:localhost:7233}")
  private String target;

  @Value("${temporal.namespace:default}")
  private String namespace;

  @Bean
  public WorkflowServiceStubs workflowServiceStubs() {
    return WorkflowServiceStubs.newServiceStubs(
        WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
  }

  @Bean
  public WorkflowClient workflowClient(WorkflowServiceStubs service) {
    return WorkflowClient.newInstance(
        service,
        WorkflowClientOptions.newBuilder()
            .setNamespace(namespace)
            .setDataConverter(LenientDataConverter.instance())
            .build());
  }
}
