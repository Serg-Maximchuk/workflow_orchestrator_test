package com.example.sil.shared.orders;

import java.time.Instant;
import java.util.List;

/**
 * The seam between the API and whichever workflow engine is fulfilling orders.
 *
 * <p>This interface is the reason a second engine can be added rather than swapped in: everything
 * above it - the TMF contract, validation, idempotency, persistence - is written once, and an
 * engine module supplies only this implementation plus its process definitions. It is deliberately
 * small, and deliberately free of engine vocabulary: no process definition keys, no jobs, no
 * variables. If a method here starts needing those, the abstraction is in the wrong place.
 */
public interface OrderOrchestrator {

    /**
     * Starts fulfilment of an order that has already been stored.
     *
     * @return an engine-specific instance identifier, recorded on the order for traceability
     */
    String startOrderFulfilment(ServiceOrder order);

    /**
     * Tells a waiting order that the supplier has finished activating its number.
     *
     * <p>This is the inbound half of an asynchronous integration: the process is parked on a
     * message, and this delivers it. Correlation is by order id rather than by any engine
     * identifier, so the supplier's callback never has to know what a process instance is.
     *
     * @throws NoWaitingOrderException when no order is waiting for this - a late, duplicate or
     *     simply unknown callback, which must not create state out of thin air
     */
    void notifyNumberActivated(String orderId, boolean activated, String reason);

    /**
     * What has happened to this order so far, newest step last. Read from the engine's own history,
     * which is the only place that knows how long each step took and which path was taken.
     */
    List<TimelineEntry> timelineOf(String orderId);

    /**
     * One completed or active step of the journey.
     *
     * @param name human-readable step name, taken from the process model
     * @param startedAt when the step began
     * @param endedAt when it finished, or null while it is still running
     * @param durationMillis how long it took, or null while it is still running
     */
    record TimelineEntry(String name, Instant startedAt, Instant endedAt, Long durationMillis) {}

    /** Raised when a callback arrives for an order that is not waiting for one. */
    class NoWaitingOrderException extends RuntimeException {
        public NoWaitingOrderException(String orderId) {
            super("No order " + orderId + " is waiting for a number activation callback");
        }
    }
}
