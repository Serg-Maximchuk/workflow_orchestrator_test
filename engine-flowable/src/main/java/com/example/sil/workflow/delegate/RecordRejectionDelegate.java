package com.example.sil.workflow.delegate;

import com.example.sil.shared.orders.ServiceOrder;
import com.example.sil.shared.orders.ServiceOrderRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sits between the rejection boundary event and the cancel end event that unwinds the transaction.
 *
 * <p>It exists for two reasons. The honest one: it is where a rejection is recorded, symmetrically
 * with the SLA timeout path. The practical one: Flowable will not let an error boundary event flow
 * straight into a cancel end event - it fails resolving the boundary event's parent scope while
 * tearing the transaction down - and a normal activity in between gives the boundary event a clean
 * place to complete first.
 */
@Component("recordRejectionDelegate")
public class RecordRejectionDelegate extends AbstractOrderDelegate {

    private static final Logger log = LoggerFactory.getLogger(RecordRejectionDelegate.class);

    public RecordRejectionDelegate(ServiceOrderRepository orders) {
        super(orders);
    }

    @Override
    protected void executeStep(ServiceOrder order, DelegateExecution execution) {
        log.warn("Supplier rejected activation for order {}, unwinding provisioning", order.getId());
    }
}
