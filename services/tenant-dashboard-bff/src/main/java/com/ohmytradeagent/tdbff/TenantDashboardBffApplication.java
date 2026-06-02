package com.ohmytradeagent.tdbff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Tenant-facing read-only BFF. Off-ingress ClusterIP service: the only caller is the Next.js
 * dashboard server, which injects a verified {@code X-Tenant-Id} behind a shared service token
 * ({@code ServiceTokenFilter}). Translates HTTP to read-only Temporal queries + jOOQ reads against
 * the orchestrator's {@code audit_log} and the exec broker's {@code order_intent_journal}, and
 * starts the short-lived {@code AccountSnapshotWorkflow} for account equity. Hosts no Temporal
 * worker and owns no trading-state schema.
 */
@SpringBootApplication
public class TenantDashboardBffApplication {

  public static void main(String[] args) {
    SpringApplication.run(TenantDashboardBffApplication.class, args);
  }
}
