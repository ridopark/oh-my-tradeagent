package com.ohmytradeagent.orchestrator.alert;

import com.ohmytradeagent.contract.AuditEvent;

/**
 * Issue #302: internal Spring application event published by {@code AuditActivitiesImpl.log} inside
 * the audit {@code @Transactional} boundary, carrying the {@link AuditEvent} that was just written.
 *
 * <p>It is consumed by {@link OrderFailureAlerter}'s {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT, fallbackExecution = true)} handler so the (potentially slow ~5s) Discord webhook
 * dispatch runs AFTER the audit transaction commits — never holding the audit DB transaction open
 * (the #302 fix). {@code fallbackExecution = true} preserves the no-active-transaction unit-test
 * path where {@code log()} is invoked without a Spring-managed transaction: the listener still
 * fires synchronously so the dsl-less test wiring continues to exercise the dispatch.
 */
public record AuditEventCommitted(AuditEvent event) {}
