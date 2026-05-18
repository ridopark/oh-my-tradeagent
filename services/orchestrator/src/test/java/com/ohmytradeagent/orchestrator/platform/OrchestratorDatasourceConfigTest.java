package com.ohmytradeagent.orchestrator.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Issue #56 (item 9): orchestrator-svc must point at a dedicated {@code orchestrator} Postgres
 * database, not the {@code temporal} database owned by Temporal's auto-setup. Otherwise step 4 of
 * the temporal-consolidation teardown runbook ({@code DROP DATABASE temporal}) destroys the
 * orchestrator's {@code audit_log} and {@code option_symbol_cache} tables.
 *
 * <p>Issue #84 adds two additional invariants: the application-runtime datasource default must
 * connect as the non-superuser {@code orchestrator_runtime} role, and Flyway must keep its
 * privileged {@code temporal} credentials via {@code spring.flyway.user} so DDL/GRANT migrations
 * still succeed. See {@code docs/ops/audit-retention.md §4}.
 */
class OrchestratorDatasourceConfigTest {

  @Test
  void applicationYaml_datasourceUrl_pointsAtOrchestratorDatabase() {
    String url = loadDatasourceUrl();

    assertThat(url).endsWith("/orchestrator");
  }

  @Test
  void applicationYaml_datasourceUrl_doesNotPointAtTemporalDatabase() {
    String url = loadDatasourceUrl();

    assertThat(url).doesNotContain("/temporal");
  }

  @Test
  void applicationYaml_datasourceUsername_defaultsToOrchestratorRuntime() {
    String username = stripPlaceholder(loadSpringValue("datasource", "username"));

    // Issue #84: application path connects as the least-privileged orchestrator_runtime role.
    // Postgres superusers (e.g. temporal) bypass the V3 REVOKE on audit_log, so the runtime
    // path must NOT default to temporal.
    assertThat(username).isEqualTo("orchestrator_runtime");
  }

  @Test
  void applicationYaml_flywayUsername_defaultsToTemporal() {
    String flywayUser = stripPlaceholder(loadSpringValue("flyway", "user"));

    // Issue #84: Flyway migrations need DDL+GRANT privileges (CREATE ROLE, GRANT) which the
    // constrained orchestrator_runtime role lacks. spring.flyway.user keeps the superuser-class
    // temporal credentials for migration-time only; the runtime DataSource above flips to
    // orchestrator_runtime.
    assertThat(flywayUser).isEqualTo("temporal");
  }

  private static String loadDatasourceUrl() {
    return stripPlaceholder(loadSpringValue("datasource", "url"));
  }

  @SuppressWarnings("unchecked")
  private static String loadSpringValue(String section, String key) {
    Yaml yaml = new Yaml();
    try (InputStream in =
        OrchestratorDatasourceConfigTest.class
            .getClassLoader()
            .getResourceAsStream("application.yml")) {
      assertThat(in).as("application.yml must be on the test classpath").isNotNull();
      Map<String, Object> root = yaml.load(in);
      Map<String, Object> spring = (Map<String, Object>) root.get("spring");
      assertThat(spring).as("spring section must exist in application.yml").isNotNull();
      Map<String, Object> sectionMap = (Map<String, Object>) spring.get(section);
      assertThat(sectionMap).as("spring." + section + " section must exist").isNotNull();
      Object raw = sectionMap.get(key);
      assertThat(raw).as("spring." + section + "." + key + " must exist").isNotNull();
      return raw.toString();
    } catch (java.io.IOException e) {
      throw new RuntimeException("failed to read application.yml from test classpath", e);
    }
  }

  /**
   * Strips the {@code ${ENV_VAR:default}} placeholder envelope down to the bare default value, so
   * tests can assert against the compiled-in default that ships when no env var is set.
   */
  private static String stripPlaceholder(String value) {
    int colon = value.indexOf(':');
    if (value.startsWith("${") && colon > 0 && value.endsWith("}")) {
      return value.substring(colon + 1, value.length() - 1);
    }
    return value;
  }
}
