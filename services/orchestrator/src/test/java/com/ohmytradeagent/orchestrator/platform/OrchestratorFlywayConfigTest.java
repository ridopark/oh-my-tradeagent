package com.ohmytradeagent.orchestrator.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Issue #56 (item 3): orchestrator Flyway must declare {@code baseline-version: 0} so V1 runs on
 * fresh deploys where Temporal pre-populates {@code public}. Without this, Flyway defaults the
 * baseline to v1 when {@code baseline-on-migrate: true} fires, silently skipping
 * V1__option_symbol_cache.sql.
 */
class OrchestratorFlywayConfigTest {

  @Test
  void applicationYaml_declaresFlywayBaselineOnMigrate() {
    Map<String, Object> flyway = loadFlywaySection();

    assertThat(flyway.get("baseline-on-migrate")).isEqualTo(true);
  }

  @Test
  void applicationYaml_declaresFlywayBaselineVersionZero() {
    Map<String, Object> flyway = loadFlywaySection();

    Object baselineVersion = flyway.get("baseline-version");
    assertThat(baselineVersion).isNotNull();
    // SnakeYAML may parse "0" as Integer or the string "0". Accept either.
    assertThat(baselineVersion.toString()).isEqualTo("0");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadFlywaySection() {
    Yaml yaml = new Yaml();
    try (InputStream in =
        OrchestratorFlywayConfigTest.class
            .getClassLoader()
            .getResourceAsStream("application.yml")) {
      assertThat(in).as("application.yml must be on the test classpath").isNotNull();
      Map<String, Object> root = yaml.load(in);
      Map<String, Object> spring = (Map<String, Object>) root.get("spring");
      assertThat(spring).as("spring section must exist in application.yml").isNotNull();
      Map<String, Object> flyway = (Map<String, Object>) spring.get("flyway");
      assertThat(flyway).as("spring.flyway section must exist in application.yml").isNotNull();
      return flyway;
    } catch (java.io.IOException e) {
      throw new RuntimeException("failed to read application.yml from test classpath", e);
    }
  }
}
