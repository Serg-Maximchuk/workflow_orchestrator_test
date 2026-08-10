package com.example.sil.workflow.delegate;

import com.example.sil.shared.orders.ServiceOrder;
import com.example.sil.shared.orders.ServiceOrderRepository;
import com.example.sil.shared.supplier.VoipSupplierClient;
import com.example.sil.shared.supplier.VoipSupplierClient.ShipmentStatusResponse;
import com.example.sil.workflow.OrderVariables;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/**
 * Asks the supplier whether the handset has arrived. Runs once per lap of the timer loop, which
 * may be many laps over several weeks - the process sleeps in the database between them.
 */
@Component("pollShipmentDelegate")
public class PollShipmentDelegate extends AbstractOrderDelegate {

    static final String DELIVERED = "delivered";

    private final VoipSupplierClient supplier;

    public PollShipmentDelegate(ServiceOrderRepository orders, VoipSupplierClient supplier) {
        super(orders);
        this.supplier = supplier;
    }

    @Override
    protected void executeStep(ServiceOrder order, DelegateExecution execution) {
        ShipmentStatusResponse status = supplier.getShipmentStatus(order.getSupplierShipmentId());

        order.shipmentPolled(status.status());
        execution.setVariable(OrderVariables.SHIPMENT_DELIVERED, DELIVERED.equals(status.status()));
    }
}
