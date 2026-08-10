package com.example.sil.shared.orders;

import java.time.Instant;
import java.util.List;

/**
 * Operational view of work the engine has given up on.
 *
 * <p>Retries answer the transient failures. What is left over is the interesting part: a supplier
 * whose contract changed, a bug in a delegate, a payload the adapter cannot parse. Those exhaust
 * their retries and land in a dead letter queue, where they wait for a human - and the point of
 * this port is that waiting there is <em>safe</em>. The order has not been lost or half-applied; it
 * is parked at a known step with its state intact, and can be resumed once the cause is fixed.
 *
 * <p>An engine without this is an engine whose failures are invisible, which in practice means an
 * order that silently never completes.
 */
public interface WorkflowAdmin {

    /** Work that has exhausted its retries and is waiting for someone to look at it. */
    List<DeadLetterWork> deadLetterWork();

    /**
     * Puts a dead-lettered item back in the queue with a fresh retry budget. Used after the cause
     * has been fixed - a redeployed supplier, a corrected mapping, an unblocked account.
     */
    void retryDeadLetterWork(String workId);

    /**
     * @param workId engine identifier, needed to retry it
     * @param orderId the order this work belongs to
     * @param stepName which step of the journey failed
     * @param errorMessage the failure as the engine recorded it
     * @param failedAt when it gave up
     */
    record DeadLetterWork(
            String workId, String orderId, String stepName, String errorMessage, Instant failedAt) {}

    /** Raised when a dead letter item cannot be found, usually because it was already retried. */
    class UnknownDeadLetterWorkException extends RuntimeException {
        public UnknownDeadLetterWorkException(String workId) {
            super("No dead letter work with id " + workId);
        }
    }
}
