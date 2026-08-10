package com.example.sil.workflow.delegate;

import com.example.sil.shared.orders.ServiceOrder;
import com.example.sil.shared.orders.ServiceOrderRepository;
import com.example.sil.shared.supplier.VoipSupplierClient;
import com.example.sil.shared.supplier.VoipSupplierClient.NumberReservationResponse;
import com.example.sil.shared.supplier.VoipSupplierClient.ReserveNumberRequest;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/** Supplier operation 4 of 6. */
@Component("reserveNumberDelegate")
public class ReserveNumberDelegate extends AbstractProvisioningDelegate {

    private final VoipSupplierClient supplier;

    public ReserveNumberDelegate(ServiceOrderRepository orders, VoipSupplierClient supplier) {
        super(orders);
        this.supplier = supplier;
    }

    @Override
    protected void provision(ServiceOrder order, DelegateExecution execution) {
        NumberReservationResponse response = supplier.reserveNumber(
                new ReserveNumberRequest(order.getSupplierUserId(), areaCodeFor(order.getPostcode())));

        order.numberReserved(response.phoneNumber());
    }

    /** Placeholder for the real area code rules; a DMN table takes this over in Phase 6. */
    private String areaCodeFor(String postcode) {
        return postcode.startsWith("SW") || postcode.startsWith("EC") ? "020" : "0161";
    }
}
