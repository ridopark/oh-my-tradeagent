package com.ohmytradeagent.audit;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Issue #90 CLI entry point. Run as:
 *
 * <pre>
 * java -jar audit-svc-0.1.0-SNAPSHOT.jar \
 *   --tenant=dev --strategy=copytrade-v1 \
 *   --from=2026-05-01 --to=2026-05-02
 * </pre>
 *
 * <p>{@code --from} and {@code --to} are ISO dates (UTC midnight boundaries). The verifier reads
 * {@code audit_log} rows whose {@code occurred_at} is in {@code [from 00:00:00 UTC, to 00:00:00
 * UTC)}, runs the completeness check, prints a structured one-line summary to stdout, and exits 0
 * on a pass / 1 on any divergence.
 *
 * <p>Daily wiring lives in {@code infra/k8s/57-audit-completeness-check-cron.yaml}: a Kubernetes
 * CronJob runs this same jar nightly with {@code --from=$(yesterday) --to=$(today)} so the gate
 * operator can read "20 consecutive green days" off the CronJob's run history without reading
 * source (criterion 4).
 */
@SpringBootApplication
public class AuditCompletenessApplication implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AuditCompletenessApplication.class);

  @Autowired private AuditCompletenessVerifier verifier;
  @Autowired private ConfigurableApplicationContext context;

  public static void main(String[] args) {
    // Disable the Spring Boot banner — the structured stdout line is the only thing ops should
    // see in the CronJob run log.
    SpringApplication app = new SpringApplication(AuditCompletenessApplication.class);
    app.setLogStartupInfo(false);
    int exit = SpringApplication.exit(app.run(args));
    System.exit(exit);
  }

  @Override
  public void run(ApplicationArguments args) {
    String tenant = requiredArg(args, "tenant");
    String strategy = requiredArg(args, "strategy");
    LocalDate from = LocalDate.parse(requiredArg(args, "from"));
    LocalDate to = LocalDate.parse(requiredArg(args, "to"));

    OffsetDateTime fromTs = from.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    OffsetDateTime toTs = to.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();

    AuditCompletenessVerifier.Report report = verifier.verify(tenant, strategy, fromTs, toTs);

    // Structured one-line summary: parseable by the CronJob log scraper without code reading.
    System.out.printf(
        "audit_completeness tenant=%s strategy=%s from=%s to=%s events=%d lifecycles=%d "
            + "complete=%d score=%.2f%% divergences=%d result=%s%n",
        report.tenantId(),
        report.strategyId(),
        from,
        to,
        report.totalEvents(),
        report.totalLifecycles(),
        report.completeLifecycles(),
        report.score(),
        report.divergences().size(),
        report.passed() ? "PASS" : "FAIL");
    if (!report.passed()) {
      for (Divergence d : report.divergences()) {
        System.out.printf(
            "  divergence kind=%s correlation_id=%s detail=%s%n",
            d.kind(), d.correlationId(), d.detail());
      }
    }
    // Spring Boot will read this exit code via SpringApplication.exit and propagate it.
    if (!report.passed()) {
      context.close();
      System.exit(1);
    }
  }

  private static String requiredArg(ApplicationArguments args, String name) {
    List<String> vals = args.getOptionValues(name);
    if (vals == null || vals.isEmpty()) {
      throw new IllegalArgumentException(
          "missing required argument --" + name + " (see Javadoc for usage)");
    }
    return vals.get(0);
  }
}
