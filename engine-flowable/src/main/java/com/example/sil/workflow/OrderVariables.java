package com.example.sil.workflow;

/**
 * Names of the process variables the order workflow carries.
 *
 * <p>Kept deliberately small. Process variables are the engine's data, copied into every history
 * row, so putting the whole order in there would duplicate the {@code service_order} table into
 * {@code ACT_HI_VARINST} and immediately let the two drift apart. The process carries the order id
 * and the correlation id; everything else is read from the order row by the delegates.
 */
public final class OrderVariables {

    /** Primary key of the {@code service_order} row this instance is fulfilling. */
    public static final String ORDER_ID = "orderId";

    /** Correlation id of the request that submitted the order, for logging on job threads. */
    public static final String CORRELATION_ID = "correlationId";

    /** Read by the gateway that decides whether the shipment poll loop goes round again. */
    public static final String SHIPMENT_DELIVERED = "shipmentDelivered";

    /** How long the process sleeps between shipment polls; read by the timer in the model. */
    public static final String SHIPMENT_POLL_DELAY = "shipmentPollDelay";

    /**
     * Whether the client has asked to cancel, refreshed from the order after every step and read
     * by the checkpoint gateway that follows it.
     */
    public static final String CANCELLATION_REQUESTED = "cancellationRequested";

    /**
     * Whether the unwind was requested by the client rather than caused by a failure. Read by the
     * gateway after compensation, which is the point where the original cause is no longer known.
     */
    public static final String CANCELLED_BY_CLIENT = "cancelledByClient";

    private OrderVariables() {}
}
