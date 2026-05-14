package com.ohmytradeagent.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Operator-facing REST gateway. No Temporal worker hosted: this service is a thin translator from
 * HTTP to {@link io.temporal.client.WorkflowClient} calls (list, query, update) plus a direct jOOQ
 * read against the orchestrator's {@code audit_log} table.
 */
@SpringBootApplication
public class ApiGatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(ApiGatewayApplication.class, args);
  }
}
