package com.example.sil.workflow.delegate;

import com.example.sil.shared.messaging.OrderEventPublisher;
import com.example.sil.shared.orders.ServiceOrder;
import com.example.sil.shared.orders.ServiceOrderRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Final state for a client cancellation. Reached only after compensation has finished, so by the
 * time an order reads "cancelled" nothing is left behind at the supplier.
 */
@Component("cancelOrderDelegate")
public class CancelOrderDelegate extends AbstractOrderDelegate {

    private static final Logger log = LoggerFactory.getLogger(CancelOrderDelegate.class);

    private final OrderEventPublisher events;

    public CancelOrderDelegate(ServiceOrderRepository orders, OrderEventPublisher events) {
        super(orders);
        this.events = events;
    }

    @Override
    protected void executeStep(ServiceOrder order, DelegateExecution execution) {
        order.cancelled();
        events.orderReachedFinalState(order, OrderEventPublisher.ORDER_CANCELLED);
        log.info("Order {} cancelled and fully compensated", order.getId());
    }
}
