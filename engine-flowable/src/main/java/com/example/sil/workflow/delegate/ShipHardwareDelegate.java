package com.example.sil.workflow.delegate;

import com.example.sil.shared.orders.ServiceOrder;
import com.example.sil.shared.orders.ServiceOrderRepository;
import com.example.sil.shared.supplier.VoipSupplierClient;
import com.example.sil.shared.supplier.VoipSupplierClient.ShipHardwareRequest;
import com.example.sil.shared.supplier.VoipSupplierClient.ShipmentResponse;
import com.example.sil.workflow.OrderVariables;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/** Supplier operation 6 of 6: request the handset shipment. */
@Component("shipHardwareDelegate")
public class ShipHardwareDelegate extends AbstractOrderDelegate {

    private final VoipSupplierClient supplier;

    public ShipHardwareDelegate(ServiceOrderRepository orders, VoipSupplierClient supplier) {
        super(orders);
        this.supplier = supplier;
    }

    @Override
    protected void executeStep(ServiceOrder order, DelegateExecution execution) {
        ShipmentResponse response = supplier.shipHardware(
                new ShipHardwareRequest(order.getId(), order.getPostcode(), "VOIP_HANDSET"));

        order.hardwareShipped(response.shipmentId());
        // The gateway after the poll step reads this, so it must exist before the loop is entered.
        execution.setVariable(OrderVariables.SHIPMENT_DELIVERED, false);
    }
}
