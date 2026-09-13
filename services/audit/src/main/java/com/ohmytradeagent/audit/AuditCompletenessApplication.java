package com.ohmytradeagent.audit;

import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Issue #90 CLI entry point. Run as:
 *
 * <pre>
 * # every (tenant, strategy) with activity in the window — the CronJob's mode
 * java -jar audit-svc-0.1.0-SNAPSHOT.jar --from=2026-05-01 --to=2026-05-02
 *
 * # one pair, for ad-hoc investigation
 * java -jar audit-svc-0.1.0-SNAPSHOT.jar \
 *   --tenant=prod_real --strategy=copytrade-v1 \
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
public class AuditCompletenessApplication implements ApplicationRunner, ExitCodeGenerator {

  @Autowired private CompletenessRunner runner;

  private int exitCode = 0;

  public static void main(String[] args) {
    // Disable the Spring Boot banner — the structured stdout line is the only thing ops should
    // see in the CronJob run log.
    SpringApplication app = new SpringApplication(AuditCompletenessApplication.class);
    app.setLogStartupInfo(false);
    System.exit(SpringApplication.exit(app.run(args)));
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }

  @Override
  public void run(ApplicationArguments args) {
    // tenant/strategy are now OPTIONAL. Omitted (the CronJob's mode) means "every pair with
    // activity
    // in the window", so a new tenant is covered without editing a manifest — the hardcoded
    // TENANT=dev is exactly what made this job verify nothing for months (#853).
    String tenant = optionalArg(args, "tenant");
    String strategy = optionalArg(args, "strategy");
    if ((tenant == null) != (strategy == null)) {
      throw new IllegalArgumentException(
          "--tenant and --strategy must be given together, or both omitted to verify every pair");
    }
    LocalDate from = LocalDate.parse(requiredArg(args, "from"));
    LocalDate to = LocalDate.parse(requiredArg(args, "to"));
    exitCode = runner.run(tenant, strategy, from, to);
  }

  private static String optionalArg(ApplicationArguments args, String name) {
    List<String> vals = args.getOptionValues(name);
    return (vals == null || vals.isEmpty() || vals.get(0).isBlank()) ? null : vals.get(0);
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
