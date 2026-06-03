package com.ohmytradeagent.orchestrator.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.AuditEvent;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issue #302: proves the {@link OrderFailureAlerter#onAuditCommitted} dispatch genuinely runs via
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} — i.e. the (potentially slow ~5s)
 * Discord webhook fires AFTER the surrounding transaction commits and therefore cannot hold the
 * audit DB transaction open.
 *
 * <p>Uses a real Spring {@link AnnotationConfigApplicationContext} plus a minimal no-op {@link
 * PlatformTransactionManager} so the after-commit synchronization actually engages without a
 * database. The {@code fallbackExecution = true} path (no active transaction) is covered separately
 * in {@code AuditActivitiesImplTest}.
 */
class OrderFailureAlerterAfterCommitTest {

  private AnnotationConfigApplicationContext ctx;

  @BeforeEach
  void setUp() {
    ctx = new AnnotationConfigApplicationContext(TestConfig.class);
  }

  @AfterEach
  void tearDown() {
    if (ctx != null) {
      ctx.close();
    }
  }

  @Test
  void listenerFiresOnlyAfterTransactionCommits() {
    OrderingWebhookClient webhook = ctx.getBean(OrderingWebhookClient.class);
    TransactionTemplate tx = ctx.getBean(TransactionTemplate.class);

    tx.executeWithoutResult(
        status -> {
          // Publish the internal event INSIDE the transaction (mirrors AuditActivitiesImpl.log).
          ctx.publishEvent(new AuditEventCommitted(event("SignalRejected")));
          // The AFTER_COMMIT listener must NOT have dispatched yet — we are still mid-transaction.
          assertThat(webhook.dispatched).as("webhook must not fire before commit").isEmpty();
          webhook.markCommitBoundary();
        });

    // After the transaction commits, the listener fires the webhook exactly once.
    assertThat(webhook.dispatched).hasSize(1);
    // And it fired strictly AFTER the in-transaction work reached the commit boundary.
    assertThat(webhook.dispatchedAfterCommitBoundary).isTrue();
  }

  @Test
  void rolledBackTransactionDoesNotDispatch() {
    OrderingWebhookClient webhook = ctx.getBean(OrderingWebhookClient.class);
    TransactionTemplate tx = ctx.getBean(TransactionTemplate.class);

    tx.executeWithoutResult(
        status -> {
          ctx.publishEvent(new AuditEventCommitted(event("SignalRejected")));
          status.setRollbackOnly();
        });

    // AFTER_COMMIT must not fire on rollback.
    assertThat(webhook.dispatched).isEmpty();
  }

  @Test
  void throwingWebhookOnDispatchThreadIsSwallowed() {
    // Belt-and-suspenders: even when the dispatch (webhook) throws, the after-commit listener path
    // must swallow it — the commit already happened, the caller must never see the dispatch error.
    try (AnnotationConfigApplicationContext throwingCtx =
        new AnnotationConfigApplicationContext(ThrowingTestConfig.class)) {
      TransactionTemplate tx = throwingCtx.getBean(TransactionTemplate.class);
      org.assertj.core.api.Assertions.assertThatCode(
              () ->
                  tx.executeWithoutResult(
                      status ->
                          throwingCtx.publishEvent(
                              new AuditEventCommitted(event("SignalRejected")))))
          .doesNotThrowAnyException();
    }
  }

  private static AuditEvent event(String kind) {
    AuditEvent ev = new AuditEvent();
    ev.setSchemaVersion(1L);
    ev.setTenantId("dev");
    ev.setStrategyId("copytrade-v1");
    ev.setEventId("00000000-0000-4000-8000-00000000aaaa");
    ev.setOccurredAt(OffsetDateTime.parse("2026-05-29T07:00:00Z"));
    ev.setKind(kind);
    ev.setActor("workflow:CopytradeSignalWorkflow");
    ev.setWorkflowId("wf-1");
    ev.setCorrelationId("corr-1");
    ev.setSubject(Map.of("signal_id", "111:0", "option_symbol", "AAPL260116C00200000"));
    return ev;
  }

  /** Records dispatch order so the test can prove AFTER_COMMIT ordering. */
  static final class OrderingWebhookClient implements WebhookClient {
    final List<String> dispatched = new CopyOnWriteArrayList<>();
    volatile boolean commitBoundaryReached = false;
    volatile boolean dispatchedAfterCommitBoundary = false;

    void markCommitBoundary() {
      commitBoundaryReached = true;
    }

    @Override
    public void post(String content) {
      record(content);
    }

    @Override
    public void postEmbed(WebhookEmbed embed) {
      record(embed.title());
    }

    private void record(String hint) {
      dispatched.add(hint);
      dispatchedAfterCommitBoundary = commitBoundaryReached;
    }
  }

  @Configuration
  @EnableTransactionManagement
  static class TestConfig {
    @Bean
    OrderingWebhookClient webhookClient() {
      return new OrderingWebhookClient();
    }

    @Bean
    OrderFailureAlerter orderFailureAlerter(OrderingWebhookClient webhookClient) {
      return new OrderFailureAlerter(
          webhookClient, "SignalRejected,OrphanSTC,EntryExpired", /* signalFeedEnabled= */ true);
    }

    @Bean
    PlatformTransactionManager transactionManager() {
      return new NoOpTransactionManager();
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
      return new TransactionTemplate(tm);
    }
  }

  @Configuration
  @EnableTransactionManagement
  static class ThrowingTestConfig {

    @Bean
    WebhookClient webhookClient() {
      return new WebhookClient() {
        @Override
        public void post(String content) {
          throw new RuntimeException("discord down");
        }

        @Override
        public void postEmbed(WebhookEmbed embed) {
          throw new RuntimeException("discord down");
        }
      };
    }

    @Bean
    OrderFailureAlerter orderFailureAlerter(WebhookClient webhookClient) {
      return new OrderFailureAlerter(
          webhookClient, "SignalRejected,OrphanSTC,EntryExpired", /* signalFeedEnabled= */ true);
    }

    @Bean
    PlatformTransactionManager transactionManager() {
      return new NoOpTransactionManager();
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
      return new TransactionTemplate(tm);
    }
  }

  /**
   * Minimal {@link PlatformTransactionManager} that engages Spring's transaction synchronization
   * (so {@code @TransactionalEventListener(AFTER_COMMIT)} fires) without any real datasource. It
   * activates synchronization on begin and triggers the registered after-commit synchronizations on
   * commit, the after-completion (rolled-back) callbacks on rollback.
   */
  static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

    NoOpTransactionManager() {
      setTransactionSynchronization(SYNCHRONIZATION_ALWAYS);
    }

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      // no-op
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      // no-op — AbstractPlatformTransactionManager drives the synchronization callbacks
      // (afterCommit/afterCompletion) for us around this method.
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      // no-op
    }

    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) {
      // no-op
    }
  }
}
