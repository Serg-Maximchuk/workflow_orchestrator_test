package com.example.sil.workflow.delegate;

import com.example.sil.shared.orders.ServiceOrder;
import com.example.sil.shared.orders.ServiceOrderRepository;
import com.example.sil.shared.supplier.VoipSupplierClient;
import com.example.sil.shared.supplier.VoipSupplierClient.CreateCustomerRequest;
import com.example.sil.shared.supplier.VoipSupplierClient.CustomerResponse;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/** Supplier operation 1 of 6, wired into the process as {@code ${createCustomerDelegate}}. */
@Component("createCustomerDelegate")
public class CreateCustomerDelegate extends AbstractProvisioningDelegate {

    private final VoipSupplierClient supplier;

    public CreateCustomerDelegate(ServiceOrderRepository orders, VoipSupplierClient supplier) {
        super(orders);
        this.supplier = supplier;
    }

    @Override
    protected void provision(ServiceOrder order, DelegateExecution execution) {
        CustomerResponse response = supplier.createCustomer(new CreateCustomerRequest(
                order.getExternalId(),
                order.getCustomerName(),
                order.getCustomerEmail(),
                order.getPostcode()));

        order.customerCreated(response.customerId());
    }
}
