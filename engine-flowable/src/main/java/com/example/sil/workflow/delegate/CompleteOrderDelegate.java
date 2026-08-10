package com.example.sil.workflow.delegate;

import com.example.sil.shared.messaging.OrderEventPublisher;
import com.example.sil.shared.orders.ServiceOrder;
import com.example.sil.shared.orders.ServiceOrderRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Marks the order complete. Separate from the last supplier call on purpose: "the supplier
 * reserved a number" and "the order is done" are different facts, and once inventory and
 * notifications arrive in later phases they hang off this step rather than off a provisioning call.
 */
@Component("completeOrderDelegate")
public class CompleteOrderDelegate extends AbstractOrderDelegate {

    private static final Logger log = LoggerFactory.getLogger(CompleteOrderDelegate.class);

    private final OrderEventPublisher events;

    public CompleteOrderDelegate(ServiceOrderRepository orders, OrderEventPublisher events) {
        super(orders);
        this.events = events;
    }

    @Override
    protected void executeStep(ServiceOrder order, DelegateExecution execution) {
        order.completed();
        events.orderReachedFinalState(order, OrderEventPublisher.ORDER_COMPLETED);
        log.info("Order {} completed with number {}", order.getId(), order.getPhoneNumber());
    }
}
