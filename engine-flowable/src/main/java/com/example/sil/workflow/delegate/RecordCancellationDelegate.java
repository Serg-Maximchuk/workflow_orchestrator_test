package com.example.sil.workflow.delegate;

import com.example.sil.shared.orders.ServiceOrder;
import com.example.sil.shared.orders.ServiceOrderRepository;
import com.example.sil.workflow.OrderVariables;
import org.flowable.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs when the client's cancellation message interrupts fulfilment, before any unwinding starts.
 *
 * <p>Its only job is to record <em>why</em> the transaction is being cancelled. All three unwind
 * paths converge on the same cancel boundary event, and by the time that fires the cause is no
 * longer on the stack - so the reason has to be a process variable, set here.
 */
@Component("recordCancellationDelegate")
public class RecordCancellationDelegate extends AbstractOrderDelegate {

    private static final Logger log = LoggerFactory.getLogger(RecordCancellationDelegate.class);

    public RecordCancellationDelegate(ServiceOrderRepository orders) {
        super(orders);
    }

    @Override
    protected void executeStep(ServiceOrder order, DelegateExecution execution) {
        log.info("Cancellation requested for order {}, unwinding provisioning", order.getId());
        // Set on the process instance, not the local scope: the gateway that reads it lives
        // outside the transaction that is about to be torn down.
        execution.setVariable(OrderVariables.CANCELLED_BY_CLIENT, true);
    }
}
