package com.example.sil.workflow.delegate;

import com.example.sil.shared.orders.ServiceOrder;
import com.example.sil.shared.orders.ServiceOrderRepository;
import com.example.sil.shared.supplier.VoipSupplierClient;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/**
 * The undo half of the saga: one handler per provisioning step, each attached to its step by a
 * compensation boundary event in the model.
 *
 * <p>Grouped in one file because they are one idea, and reading them together is how the symmetry
 * with the provisioning steps stays visible.
 *
 * <p>Every handler is a no-op when the reference it undoes is absent. That is not defensive
 * padding: compensation is invoked for activities the engine recorded as completed, and a step can
 * complete without leaving a reference behind - a retried call whose first attempt already
 * succeeded, or an activation the supplier accepted but never confirmed. Undoing nothing has to be
 * safe, because the alternative is a null pointer in the middle of an unwind.
 */
public final class CompensationDelegates {

    private CompensationDelegates() {}

    @Component("deleteCustomerDelegate")
    public static class DeleteCustomer extends AbstractOrderDelegate {

        private final VoipSupplierClient supplier;

        public DeleteCustomer(ServiceOrderRepository orders, VoipSupplierClient supplier) {
            super(orders);
            this.supplier = supplier;
        }

        @Override
        protected void executeStep(ServiceOrder order, DelegateExecution execution) {
            if (order.getSupplierCustomerId() == null) {
                return;
            }
            supplier.deleteCustomer(order.getSupplierCustomerId());
            order.customerDeleted();
        }
    }

    @Component("deleteSubscriptionDelegate")
    public static class DeleteSubscription extends AbstractOrderDelegate {

        private final VoipSupplierClient supplier;

        public DeleteSubscription(ServiceOrderRepository orders, VoipSupplierClient supplier) {
            super(orders);
            this.supplier = supplier;
        }

        @Override
        protected void executeStep(ServiceOrder order, DelegateExecution execution) {
            if (order.getSupplierSubscriptionId() == null) {
                return;
            }
            supplier.deleteSubscription(order.getSupplierSubscriptionId());
            order.subscriptionDeleted();
        }
    }

    @Component("deleteUserDelegate")
    public static class DeleteUser extends AbstractOrderDelegate {

        private final VoipSupplierClient supplier;

        public DeleteUser(ServiceOrderRepository orders, VoipSupplierClient supplier) {
            super(orders);
            this.supplier = supplier;
        }

        @Override
        protected void executeStep(ServiceOrder order, DelegateExecution execution) {
            if (order.getSupplierUserId() == null) {
                return;
            }
            supplier.deleteUser(order.getSupplierUserId());
            order.userDeleted();
        }
    }

    @Component("releaseNumberDelegate")
    public static class ReleaseNumber extends AbstractOrderDelegate {

        private final VoipSupplierClient supplier;

        public ReleaseNumber(ServiceOrderRepository orders, VoipSupplierClient supplier) {
            super(orders);
            this.supplier = supplier;
        }

        @Override
        protected void executeStep(ServiceOrder order, DelegateExecution execution) {
            if (order.getPhoneNumber() == null) {
                return;
            }
            supplier.releaseNumber(order.getPhoneNumber());
            order.numberReleased();
        }
    }

    @Component("cancelActivationDelegate")
    public static class CancelActivation extends AbstractOrderDelegate {

        private final VoipSupplierClient supplier;

        public CancelActivation(ServiceOrderRepository orders, VoipSupplierClient supplier) {
            super(orders);
            this.supplier = supplier;
        }

        @Override
        protected void executeStep(ServiceOrder order, DelegateExecution execution) {
            if (order.getSupplierActivationId() == null) {
                return;
            }
            supplier.cancelActivation(order.getSupplierActivationId());
            order.activationCancelled();
        }
    }

    @Component("cancelShipmentDelegate")
    public static class CancelShipment extends AbstractOrderDelegate {

        private final VoipSupplierClient supplier;

        public CancelShipment(ServiceOrderRepository orders, VoipSupplierClient supplier) {
            super(orders);
            this.supplier = supplier;
        }

        @Override
        protected void executeStep(ServiceOrder order, DelegateExecution execution) {
            if (order.getSupplierShipmentId() == null) {
                return;
            }
            supplier.cancelShipment(order.getSupplierShipmentId());
            order.shipmentCancelled();
        }
    }
}
