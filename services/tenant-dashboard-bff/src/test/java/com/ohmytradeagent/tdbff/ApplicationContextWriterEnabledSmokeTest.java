package com.ohmytradeagent.tdbff;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The same full-context boot as {@link ApplicationContextSmokeTest}, but with the writer-side flags
 * ON — which is the only configuration that constructs the beans this feature actually ships.
 *
 * <p>WHY A SECOND CONTEXT TEST. Its sibling boots with {@code dashboard.writer.enabled} at its
 * default {@code false}, so every bean behind that flag — the writer {@code DSLContext}, {@code
 * OptionsChatRepository}, the ingest/media controllers, and the retention scheduler — is
 * conditional-excluded and never constructed. A wiring fault in any of them therefore ships GREEN
 * and only appears when the pod starts on the cluster, where both flags are true.
 *
 * <p>That is not hypothetical: it is exactly how {@code PortfolioHistoryClient} reached the homelab
 * in PR #486, and {@code OptionsChatRetention} reproduced it — two declared constructors with no
 * {@code @Autowired}, which makes Spring fall back to a no-arg constructor that does not exist and
 * abort context refresh. Because the BFF is the dashboard's ONLY backend, that is not a degraded
 * {@code /options-chat}; it is {@code /live}, {@code /config} and every operator surface down with
 * it.
 *
 * <p>Still hermetic: the writer {@code DataSource}/{@code DSLContext} construct lazily and open no
 * connection until first query, and Flyway stays disabled, so enabling the flag costs no database.
 */
@SpringBootTest(
    classes = TenantDashboardBffApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.flyway.enabled=false",
      "DASHBOARD_READONLY_PASSWORD=test-not-used",
      "DASHBOARD_WRITER_PASSWORD=test-not-used",
      // The two flags that gate the whole /options-chat write path, together — as on the cluster.
      "dashboard.writer.enabled=true",
      "options-chat.enabled=true"
    })
class ApplicationContextWriterEnabledSmokeTest {

  @MockitoBean private WorkflowServiceStubs workflowServiceStubs;
  @MockitoBean private WorkflowClient workflowClient;
  @MockitoBean private StringRedisTemplate stringRedisTemplate;

  @Test
  void contextLoadsWithTheWriterPathEnabled() {
    // Intentionally empty: a bean that cannot be constructed throws during the context
    // initialization above. This is the guard for every @Component behind these two flags, not just
    // the one that prompted it.
  }
}
