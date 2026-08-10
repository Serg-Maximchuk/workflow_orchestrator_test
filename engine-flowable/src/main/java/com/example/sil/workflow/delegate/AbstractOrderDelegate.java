package com.example.sil.workflow.delegate;

import com.example.sil.shared.correlation.CorrelationIdFilter;
import com.example.sil.shared.orders.ServiceOrder;
import com.example.sil.shared.orders.ServiceOrderRepository;
import com.example.sil.workflow.OrderVariables;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.MDC;

/**
 * Shared plumbing for the service task delegates: load the order, restore the correlation id, run
 * the step.
 *
 * <p>Restoring the MDC matters more than it looks. These delegates run on job executor threads,
 * not on the HTTP thread that submitted the order, so without this the log lines for the actual
 * supplier calls would carry no correlation id at all - and those are precisely the lines anyone
 * debugging a stuck order wants to find.
 */
abstract class AbstractOrderDelegate implements JavaDelegate {

    protected final ServiceOrderRepository orders;

    protected AbstractOrderDelegate(ServiceOrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public final void execute(DelegateExecution execution) {
        String orderId = (String) execution.getVariable(OrderVariables.ORDER_ID);
        String correlationId = (String) execution.getVariable(OrderVariables.CORRELATION_ID);

        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        try {
            ServiceOrder order = orders.findById(orderId).orElseThrow(() ->
                    new IllegalStateException("Order " + orderId + " is gone while its workflow runs"));
            executeStep(order, execution);
            orders.save(order);

            // Republished after every step so the checkpoint gateway that follows reads a value
            // that is at most one step old. The client can cancel at any moment, including while
            // this very call was in flight, and the intent lands on the order rather than on the
            // process - so this is where the two meet.
            execution.setVariable(
                    OrderVariables.CANCELLATION_REQUESTED, order.isCancellationRequested());
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    /**
     * Performs one step of the journey against the loaded order. Anything thrown here fails the
     * job, which is what hands the problem to the engine's retry machinery.
     */
    protected abstract void executeStep(ServiceOrder order, DelegateExecution execution);
}
