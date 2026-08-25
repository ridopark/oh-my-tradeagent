package com.ohmytradeagent.tdbff.config;

// COPIED FROM services/api-gateway/.../config/TemporalClientConfig.java — keep in sync.
import com.ohmytradeagent.contract.temporal.LenientDataConverter;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
            // #772: lenient payload deserialization so a since-removed schema field in a recorded
            // history can never wedge a replay. Write-path schema validation stays strict.
            .setDataConverter(LenientDataConverter.instance())
            .build());
  }
}
