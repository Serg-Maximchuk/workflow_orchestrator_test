package com.example.sil.workflow.delegate;

import com.example.sil.shared.orders.ServiceOrder;
import com.example.sil.shared.orders.ServiceOrderRepository;
import com.example.sil.shared.supplier.VoipSupplierClient;
import com.example.sil.shared.supplier.VoipSupplierClient.ActivateNumberRequest;
import com.example.sil.shared.supplier.VoipSupplierClient.ActivationAcceptedResponse;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Supplier operation 5 of 6: ask for the reserved number to be activated.
 *
 * <p>Where the technical/business distinction is actually made. A 4xx from the supplier means the
 * request will never succeed - the number is already ported, the address does not match - so it is
 * turned into a {@link BpmnError} and the process takes its rejection path. Anything else (a 5xx,
 * a timeout, a connection reset) is left to propagate as an exception, which is what puts the job
 * back in the queue to be retried.
 *
 * <p>Getting this backwards is a classic integration bug in both directions: retrying a rejection
 * hammers the supplier and delays telling the customer, while treating a timeout as a rejection
 * fails an order that would have gone through on the next attempt.
 */
@Component("requestActivationDelegate")
public class RequestActivationDelegate extends AbstractOrderDelegate {

    static final String SUPPLIER_REJECTED = "SUPPLIER_REJECTED";

    private final VoipSupplierClient supplier;

    public RequestActivationDelegate(ServiceOrderRepository orders, VoipSupplierClient supplier) {
        super(orders);
        this.supplier = supplier;
    }

    @Override
    protected void executeStep(ServiceOrder order, DelegateExecution execution) {
        try {
            ActivationAcceptedResponse response = supplier.activateNumber(new ActivateNumberRequest(
                    order.getSupplierUserId(), order.getPhoneNumber(), order.getId()));

            order.activationRequested(response.activationId());
        } catch (HttpClientErrorException rejected) {
            order.failed("Supplier rejected the activation: " + rejected.getStatusCode());
            throw new BpmnError(SUPPLIER_REJECTED, rejected.getMessage());
        }
    }
}
