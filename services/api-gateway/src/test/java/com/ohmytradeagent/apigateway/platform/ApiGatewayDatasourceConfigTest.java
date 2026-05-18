package com.ohmytradeagent.apigateway.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Issue #56 (item 9): api-gateway must read {@code audit_log} from the same dedicated {@code
 * orchestrator} Postgres database the orchestrator-svc writes it to. If api-gateway is left pointed
 * at {@code temporal}, {@code AuditController} returns empty results because the table lives
 * elsewhere.
 */
class ApiGatewayDatasourceConfigTest {

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

  @SuppressWarnings("unchecked")
  private static String loadDatasourceUrl() {
    Yaml yaml = new Yaml();
    try (InputStream in =
        ApiGatewayDatasourceConfigTest.class
            .getClassLoader()
            .getResourceAsStream("application.yml")) {
      assertThat(in).as("application.yml must be on the test classpath").isNotNull();
      Map<String, Object> root = yaml.load(in);
      Map<String, Object> spring = (Map<String, Object>) root.get("spring");
      assertThat(spring).as("spring section must exist in application.yml").isNotNull();
      Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
      assertThat(datasource).as("spring.datasource section must exist").isNotNull();
      Object rawUrl = datasource.get("url");
      assertThat(rawUrl).as("spring.datasource.url must exist").isNotNull();
      String url = rawUrl.toString();
      int colon = url.indexOf(':');
      if (url.startsWith("${") && colon > 0 && url.endsWith("}")) {
        url = url.substring(colon + 1, url.length() - 1);
      }
      return url;
    } catch (java.io.IOException e) {
      throw new RuntimeException("failed to read application.yml from test classpath", e);
    }
  }
}
