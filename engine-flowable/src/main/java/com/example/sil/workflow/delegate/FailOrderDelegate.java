package com.example.sil.workflow.delegate;

import com.example.sil.shared.messaging.OrderEventPublisher;
import com.example.sil.shared.orders.ServiceOrder;
import com.example.sil.shared.orders.ServiceOrderRepository;
import com.example.sil.shared.orders.ServiceOrderState;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/**
 * Single exit for every failure path. Whatever set the reason - a supplier rejection, a breached
 * SLA - this is where the order reaches its final state, so there is exactly one place to hang
 * notifications and, from Phase 4, compensation.
 */
@Component("failOrderDelegate")
public class FailOrderDelegate extends AbstractOrderDelegate {

    private final OrderEventPublisher events;

    public FailOrderDelegate(ServiceOrderRepository orders, OrderEventPublisher events) {
        super(orders);
        this.events = events;
    }

    @Override
    protected void executeStep(ServiceOrder order, DelegateExecution execution) {
        if (order.getState() != ServiceOrderState.FAILED) {
            order.failed("Order fulfilment failed");
        }
        events.orderReachedFinalState(order, OrderEventPublisher.ORDER_FAILED);
    }
}
