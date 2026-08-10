package com.example.sil.workflow;

import com.example.sil.shared.orders.OrderOrchestrator;
import com.example.sil.shared.orders.ServiceOrder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
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

    private final RuntimeService runtimeService;
    private final HistoryService historyService;

    public FlowableOrderOrchestrator(RuntimeService runtimeService, HistoryService historyService) {
        this.runtimeService = runtimeService;
        this.historyService = historyService;
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
                        OrderVariables.CORRELATION_ID, order.getCorrelationId()));

        log.info("Started {} instance {} for order {}",
                PROCESS_DEFINITION_KEY, instance.getId(), order.getId());
        return instance.getId();
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
                .list()
                .stream()
                // Sequence flows are in the history too, but they are plumbing, not steps anyone
                // wants to read in a timeline.
                .filter(activity -> !"sequenceFlow".equals(activity.getActivityType()))
                .map(FlowableOrderOrchestrator::toTimelineEntry)
                .toList();
    }

    private static TimelineEntry toTimelineEntry(HistoricActivityInstance activity) {
        String name = activity.getActivityName() != null
                ? activity.getActivityName()
                : activity.getActivityId();

        return new TimelineEntry(
                name,
                toInstant(activity.getStartTime()),
                toInstant(activity.getEndTime()),
                activity.getDurationInMillis());
    }

    private static Instant toInstant(java.util.Date date) {
        return date == null ? null : date.toInstant();
    }
}
