package com.example.sil.workflow.delegate;

import com.example.sil.shared.orders.ServiceOrder;
import com.example.sil.shared.orders.ServiceOrderRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Reached when the activation SLA timer fires before the supplier's callback arrives. */
@Component("activationTimedOutDelegate")
public class ActivationTimedOutDelegate extends AbstractOrderDelegate {

    private static final Logger log = LoggerFactory.getLogger(ActivationTimedOutDelegate.class);

    public ActivationTimedOutDelegate(ServiceOrderRepository orders) {
        super(orders);
    }

    @Override
    protected void executeStep(ServiceOrder order, DelegateExecution execution) {
        log.warn("Activation SLA breached for order {} - no supplier callback arrived", order.getId());
        order.failed("Supplier did not confirm number activation within the agreed SLA");
    }
}
