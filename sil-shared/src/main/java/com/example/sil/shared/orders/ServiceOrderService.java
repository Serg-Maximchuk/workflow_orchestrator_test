package com.example.sil.shared.orders;

import com.example.sil.shared.correlation.CorrelationContext;
import com.example.sil.shared.idempotency.IdempotencyService;
import com.example.sil.shared.orders.OrderDtos.CreateServiceOrderRequest;
import com.example.sil.shared.orders.OrderDtos.OrderTimelineResponse;
import com.example.sil.shared.orders.OrderDtos.ServiceOrderResponse;
import com.example.sil.shared.orders.OrderDtos.SupplierRefs;
import com.example.sil.shared.orders.OrderDtos.TimelineStep;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TMF641 Service Ordering.
 *
 * <p>The ordering inside {@link #submit} is the whole point of the phase: the order row is written
 * first, and only then is a workflow started. Both happen in one transaction, so there is no window
 * in which a process is running for an order that does not exist, and none in which an order exists
 * that nobody will ever fulfil.
 *
 * <p>What this class does <em>not</em> do is call the supplier. It stores intent and hands over;
 * every supplier call now happens on a job executor thread, inside its own transaction, under the
 * engine's control. That is the shift from Phase 1: work that used to happen while the caller
 * waited is now durable work that survives this application being restarted.
 */
@Service
public class ServiceOrderService {

    private final ServiceOrderRepository repository;
    private final OrderOrchestrator orchestrator;
    private final IdempotencyService idempotencyService;

    public ServiceOrderService(
            ServiceOrderRepository repository,
            OrderOrchestrator orchestrator,
            IdempotencyService idempotencyService) {
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.idempotencyService = idempotencyService;
    }

    @Transactional
    public ServiceOrderResponse submit(CreateServiceOrderRequest request, String idempotencyKey) {
        String orderId = idempotencyService
                .execute(idempotencyKey, fingerprintOf(request), () -> storeAndStart(request))
                .resourceId();

        return findById(orderId);
    }

    @Transactional(readOnly = true)
    public ServiceOrderResponse findById(String orderId) {
        return repository.findById(orderId)
                .map(ServiceOrderService::toResponse)
                .orElseThrow(() -> new ServiceOrderNotFoundException(orderId));
    }

    @Transactional(readOnly = true)
    public List<ServiceOrderResponse> findByState(ServiceOrderState state) {
        return repository.findByStateOrderByCreatedAtDesc(state).stream()
                .map(ServiceOrderService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderTimelineResponse timelineOf(String orderId) {
        ServiceOrder order = repository.findById(orderId)
                .orElseThrow(() -> new ServiceOrderNotFoundException(orderId));

        List<TimelineStep> steps = orchestrator.timelineOf(orderId).stream()
                .map(entry -> new TimelineStep(
                        entry.name(), entry.startedAt(), entry.endedAt(), entry.durationMillis()))
                .toList();

        return new OrderTimelineResponse(orderId, order.getState().tmfName(), steps);
    }

    private String storeAndStart(CreateServiceOrderRequest request) {
        Instant now = Instant.now();
        ServiceOrder order = repository.save(ServiceOrder.builder()
                .id("so-" + UUID.randomUUID())
                .externalId(request.externalId())
                .state(ServiceOrderState.ACKNOWLEDGED)
                .customerName(request.customer().name())
                .customerEmail(request.customer().email())
                .postcode(request.place().postcode())
                .serviceSpecId(request.serviceSpecId())
                .speedMbps(request.speedMbps())
                .correlationId(CorrelationContext.currentOrNew())
                .createdAt(now)
                .updatedAt(now)
                .build());

        order.attachProcessInstance(orchestrator.startOrderFulfilment(order));
        return order.getId();
    }

    private String fingerprintOf(CreateServiceOrderRequest request) {
        return String.join("|",
                String.valueOf(request.externalId()),
                request.customer().email(),
                request.place().postcode(),
                request.serviceSpecId(),
                String.valueOf(request.speedMbps()));
    }

    private static ServiceOrderResponse toResponse(ServiceOrder order) {
        return new ServiceOrderResponse(
                order.getId(),
                order.getExternalId(),
                order.getState().tmfName(),
                new SupplierRefs(
                        order.getSupplierCustomerId(),
                        order.getSupplierSubscriptionId(),
                        order.getSupplierUserId(),
                        order.getPhoneNumber()),
                order.getFailureReason(),
                order.getCorrelationId(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }

    /** Raised when an order id is unknown, mapped to 404 by the API error handler. */
    public static class ServiceOrderNotFoundException extends RuntimeException {
        public ServiceOrderNotFoundException(String id) {
            super("No service order with id " + id);
        }
    }
}
