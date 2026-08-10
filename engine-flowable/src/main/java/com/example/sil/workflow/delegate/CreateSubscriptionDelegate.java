package com.example.sil.workflow.delegate;

import com.example.sil.shared.orders.ServiceOrder;
import com.example.sil.shared.orders.ServiceOrderRepository;
import com.example.sil.shared.supplier.VoipSupplierClient;
import com.example.sil.shared.supplier.VoipSupplierClient.CreateSubscriptionRequest;
import com.example.sil.shared.supplier.VoipSupplierClient.SubscriptionResponse;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/** Supplier operation 2 of 6. */
@Component("createSubscriptionDelegate")
public class CreateSubscriptionDelegate extends AbstractOrderDelegate {

    private final VoipSupplierClient supplier;

    public CreateSubscriptionDelegate(ServiceOrderRepository orders, VoipSupplierClient supplier) {
        super(orders);
        this.supplier = supplier;
    }

    @Override
    protected void executeStep(ServiceOrder order, DelegateExecution execution) {
        SubscriptionResponse response = supplier.createSubscription(new CreateSubscriptionRequest(
                order.getSupplierCustomerId(), order.getServiceSpecId(), order.getSpeedMbps()));

        order.subscriptionCreated(response.subscriptionId());
    }
}
