package com.example.sil.workflow.delegate;

import com.example.sil.shared.orders.ServiceOrder;
import com.example.sil.shared.orders.ServiceOrderRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A provisioning step that declines to run once the client has asked to cancel.
 *
 * <p>Without this, a cancellation costs one more supplier call than it should. The delegate loads
 * the order at the start of its own transaction, so a cancellation committed while the previous
 * step was in flight is invisible to that step - it publishes a stale "not cancelled", its
 * checkpoint waves the process through, and the next step happily provisions something the client
 * has already said they do not want, purely so it can be undone a moment later.
 *
 * <p>Reading the flag here closes that window: the step in flight when the cancellation lands is
 * the last one that touches the supplier. Everything after it is a no-op that exists only to carry
 * the token to the checkpoint that routes into the unwind.
 *
 * <p>Only for steps that are immediately followed by a cancellation checkpoint. A step that skips
 * itself with no checkpoint after it would let the process carry on as if the work had been done.
 */
abstract class AbstractProvisioningDelegate extends AbstractOrderDelegate {

    private static final Logger log = LoggerFactory.getLogger(AbstractProvisioningDelegate.class);

    protected AbstractProvisioningDelegate(ServiceOrderRepository orders) {
        super(orders);
    }

    @Override
    protected final void executeStep(ServiceOrder order, DelegateExecution execution) {
        if (order.isCancellationRequested()) {
            log.info("Skipping {} for order {}: cancellation already requested",
                    getClass().getSimpleName(), order.getId());
            return;
        }
        provision(order, execution);
    }

    /** The supplier call this step exists for. Not invoked once cancellation has been requested. */
    protected abstract void provision(ServiceOrder order, DelegateExecution execution);
}
