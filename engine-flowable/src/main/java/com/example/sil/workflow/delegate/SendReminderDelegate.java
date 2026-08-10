package com.example.sil.workflow.delegate;

import com.example.sil.shared.orders.ServiceOrder;
import com.example.sil.shared.orders.ServiceOrderRepository;
import org.flowable.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fired by the repeating non-interrupting timer while the order waits for activation. Only counts
 * the reminder here; sending the actual email is a Phase 5 outbox concern, because a notification
 * that is sent inside a transaction that later rolls back is a notification that lied.
 */
@Component("sendReminderDelegate")
public class SendReminderDelegate extends AbstractOrderDelegate {

    private static final Logger log = LoggerFactory.getLogger(SendReminderDelegate.class);

    public SendReminderDelegate(ServiceOrderRepository orders) {
        super(orders);
    }

    @Override
    protected void executeStep(ServiceOrder order, DelegateExecution execution) {
        order.reminderSent();
        log.info("Reminder {} sent to {} for order {}",
                order.getRemindersSent(), order.getCustomerEmail(), order.getId());
    }
}
