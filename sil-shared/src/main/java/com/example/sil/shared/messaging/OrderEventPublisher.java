package com.example.sil.shared.messaging;

import com.example.sil.shared.correlation.CorrelationContext;
import com.example.sil.shared.orders.ServiceOrder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records order lifecycle events for publication.
 *
 * <p>Called from inside the delegate that changes the order, so the event lands in the same
 * transaction as the change. That is the whole point: there is no arrangement of "call the broker
 * here instead" that gives the same guarantee.
 */
@Service
public class OrderEventPublisher {

    public static final String ORDER_COMPLETED = "ServiceOrderCompleted";
    public static final String ORDER_FAILED = "ServiceOrderFailed";
    public static final String ORDER_CANCELLED = "ServiceOrderCancelled";

    private final OutboxEventRepository outbox;

    /**
     * Spring Boot 4 ships Jackson 3, whose ObjectMapper lives in {@code tools.jackson}. The
     * Jackson 2 class of the same name is still on the classpath through transitive dependencies,
     * and asking for that one gets you a context that will not start.
     */
    private final ObjectMapper objectMapper;

    public OrderEventPublisher(OutboxEventRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void orderReachedFinalState(ServiceOrder order, String eventType) {
        outbox.save(new OutboxEvent(
                order.getId(), eventType, payloadOf(order, eventType), CorrelationContext.currentOrNew()));
    }

    private String payloadOf(ServiceOrder order, String eventType) {
        try {
            return objectMapper.writeValueAsString(new OrderEvent(
                    eventType,
                    order.getId(),
                    order.getExternalId(),
                    order.getState().tmfName(),
                    order.getPhoneNumber(),
                    order.getFailureReason()));
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialise the event for order " + order.getId(), e);
        }
    }

    /** What a listener receives. Deliberately small - a notification, not a data feed. */
    public record OrderEvent(
            String eventType,
            String orderId,
            String externalId,
            String state,
            String phoneNumber,
            String failureReason) {}
}
