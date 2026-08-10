package com.example.sil.workflow;

import com.example.sil.shared.orders.OrderOrchestrator;
import com.example.sil.shared.orders.ServiceOrder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Flowable's implementation of the orchestration port.
 *
 * <p>All the engine-specific vocabulary lives behind this one class: process definition keys,
 * business keys, history queries. Everything above it deals in orders.
 */
@Component
public class FlowableOrderOrchestrator implements OrderOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FlowableOrderOrchestrator.class);

    static final String PROCESS_DEFINITION_KEY = "serviceOrder";
    static final String NUMBER_ACTIVATED_MESSAGE = "numberActivated";
    static final String CANCEL_ORDER_MESSAGE = "cancelOrder";
    static final String ACTIVATION_SUCCEEDED = "activationSucceeded";
    static final String ACTIVATION_REASON = "activationReason";

    private static final Set<String> PLUMBING_ACTIVITY_TYPES = Set.of(
            "sequenceFlow", "exclusiveGateway", "parallelGateway", "inclusiveGateway",
            "boundaryEvent", "boundaryTimer", "boundaryError", "boundaryMessage");

    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final WorkflowTimingProperties timings;

    public FlowableOrderOrchestrator(
            RuntimeService runtimeService,
            HistoryService historyService,
            WorkflowTimingProperties timings) {
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.timings = timings;
    }

    @Override
    public String startOrderFulfilment(ServiceOrder order) {
        // The order id doubles as the business key, which is what makes an order findable in the
        // engine (and in any monitoring UI) without knowing its process instance id.
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                PROCESS_DEFINITION_KEY,
                order.getId(),
                Map.of(
                        OrderVariables.ORDER_ID, order.getId(),
                        OrderVariables.CORRELATION_ID, order.getCorrelationId(),
                        OrderVariables.SHIPMENT_POLL_DELAY, timings.shipmentPollDelay().toString(),
                        // Initialised up front so the gateway after compensation can read it even
                        // when nothing ever set it. An unset variable there would be an error, and
                        // an error on the unwind path is the worst possible place for one.
                        OrderVariables.CANCELLED_BY_CLIENT, false));

        log.info("Started {} instance {} for order {}",
                PROCESS_DEFINITION_KEY, instance.getId(), order.getId());
        return instance.getId();
    }

    @Override
    public void notifyNumberActivated(String orderId, boolean activated, String reason) {
        // Correlate by business key plus the name of the message the process is subscribed to.
        // Querying for the subscription rather than trusting a stored execution id is what makes
        // a duplicate or late callback safe: once the message has been delivered the subscription
        // is gone, so the second delivery finds nothing and is rejected instead of corrupting a
        // process that has already moved on.
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(orderId)
                .singleResult();

        Execution waiting = instance == null ? null : runtimeService.createExecutionQuery()
                .processInstanceId(instance.getId())
                .messageEventSubscriptionName(NUMBER_ACTIVATED_MESSAGE)
                .singleResult();

        if (waiting == null) {
            throw new NoWaitingOrderException(orderId);
        }

        log.info("Delivering {} callback for order {} (activated={})",
                NUMBER_ACTIVATED_MESSAGE, orderId, activated);
        runtimeService.messageEventReceived(
                NUMBER_ACTIVATED_MESSAGE,
                waiting.getId(),
                Map.of(ACTIVATION_SUCCEEDED, activated, ACTIVATION_REASON, reason == null ? "" : reason));
    }

    @Override
    public void cancelOrderFulfilment(String orderId) {
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(orderId)
                .singleResult();

        // No running instance means fulfilment already reached an end state. Cancelling then is
        // not a no-op to be swallowed: the caller believes work is in flight either way.
        Execution waiting = instance == null ? null : runtimeService.createExecutionQuery()
                .processInstanceId(instance.getId())
                .messageEventSubscriptionName(CANCEL_ORDER_MESSAGE)
                .singleResult();

        if (waiting == null) {
            // Cancellation is caught where the order waits on the supplier. In between - while a
            // provisioning call is actually in flight - there is nothing subscribed, and honestly
            // there is nothing sensible to do either: the call has to land before we know what
            // there is to undo.
            throw new OrderNotCancellableException(orderId);
        }

        log.info("Cancelling fulfilment of order {}", orderId);
        runtimeService.messageEventReceived(CANCEL_ORDER_MESSAGE, waiting.getId());
    }

    @Override
    public List<TimelineEntry> timelineOf(String orderId) {
        // Activity history is queried by process instance, not by business key, so the instance
        // has to be resolved first. Using the history query rather than the runtime one means the
        // timeline still works after the process has finished and its runtime state is gone.
        HistoricProcessInstance instance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(orderId)
                .singleResult();

        if (instance == null) {
            return List.of();
        }

        return historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(instance.getId())
                .orderByHistoricActivityInstanceStartTime()
                .asc()
                // Steps of the same instance can start within the same millisecond, and on a
                // timeline "A then B" has to be stable rather than whatever the database returns.
                .orderByHistoricActivityInstanceEndTime()
                .asc()
                .list()
                .stream()
                .filter(FlowableOrderOrchestrator::isReadableStep)
                .map(FlowableOrderOrchestrator::toTimelineEntry)
                .toList();
    }

    /**
     * History contains the whole execution graph; a timeline is meant to be read by a person.
     *
     * <p>Two things are dropped. Plumbing - sequence flows and gateways - because nobody follows an
     * order by its arrows. And anything the model did not bother to name, which conveniently
     * excludes armed-but-never-fired boundary events and the internal start/end events of the
     * wait subprocess. When a boundary event does fire, its effect is still visible, because the
     * task it leads to is a named step of its own.
     */
    private static boolean isReadableStep(HistoricActivityInstance activity) {
        if (activity.getActivityName() == null || activity.getActivityName().isBlank()) {
            return false;
        }
        return !PLUMBING_ACTIVITY_TYPES.contains(activity.getActivityType());
    }

    private static TimelineEntry toTimelineEntry(HistoricActivityInstance activity) {
        return new TimelineEntry(
                activity.getActivityName(),
                toInstant(activity.getStartTime()),
                toInstant(activity.getEndTime()),
                activity.getDurationInMillis());
    }

    private static Instant toInstant(java.util.Date date) {
        return date == null ? null : date.toInstant();
    }
}
