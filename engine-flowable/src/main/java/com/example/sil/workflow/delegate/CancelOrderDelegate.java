package com.example.sil.workflow.delegate;

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

    public CancelOrderDelegate(ServiceOrderRepository orders) {
        super(orders);
    }

    @Override
    protected void executeStep(ServiceOrder order, DelegateExecution execution) {
        order.cancelled();
        log.info("Order {} cancelled and fully compensated", order.getId());
    }
}
