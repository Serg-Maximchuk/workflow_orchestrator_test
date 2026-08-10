package com.example.sil.workflow.delegate;

import com.example.sil.shared.orders.ServiceOrder;
import com.example.sil.shared.orders.ServiceOrderRepository;
import com.example.sil.shared.supplier.VoipSupplierClient;
import com.example.sil.shared.supplier.VoipSupplierClient.CreateUserRequest;
import com.example.sil.shared.supplier.VoipSupplierClient.UserResponse;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/** Supplier operation 3 of 6. */
@Component("createUserDelegate")
public class CreateUserDelegate extends AbstractOrderDelegate {

    private final VoipSupplierClient supplier;

    public CreateUserDelegate(ServiceOrderRepository orders, VoipSupplierClient supplier) {
        super(orders);
        this.supplier = supplier;
    }

    @Override
    protected void executeStep(ServiceOrder order, DelegateExecution execution) {
        UserResponse response = supplier.createUser(new CreateUserRequest(
                order.getSupplierSubscriptionId(), order.getCustomerName(), order.getCustomerEmail()));

        order.userCreated(response.userId());
    }
}
