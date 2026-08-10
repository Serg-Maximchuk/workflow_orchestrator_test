package com.example.sil.workflow.delegate;

import com.example.sil.shared.orders.ServiceOrder;
import com.example.sil.shared.orders.ServiceOrderRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records that the supplier confirmed the number is live.
 *
 * <p>A separate step rather than a side effect of correlating the callback: the message correlation
 * belongs to the orchestrator, which deals in process instances and knows nothing about orders, and
 * every other change to the order row is made by a delegate. Keeping it that way means there is one
 * place to look for "what changes an order, and when".
 */
@Component("recordActivationDelegate")
public class RecordActivationDelegate extends AbstractOrderDelegate {

    private static final Logger log = LoggerFactory.getLogger(RecordActivationDelegate.class);

    public RecordActivationDelegate(ServiceOrderRepository orders) {
        super(orders);
    }

    @Override
    protected void executeStep(ServiceOrder order, DelegateExecution execution) {
        order.numberActivated();
        log.info("Number {} is live for order {}", order.getPhoneNumber(), order.getId());
    }
}
