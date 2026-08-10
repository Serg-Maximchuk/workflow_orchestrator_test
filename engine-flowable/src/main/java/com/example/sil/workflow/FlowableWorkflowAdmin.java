package com.example.sil.workflow;

import com.example.sil.shared.orders.WorkflowAdmin;
import java.time.Instant;
import java.util.List;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.flowable.job.api.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Flowable's dead letter queue, exposed through the engine-agnostic admin port.
 *
 * <p>Flowable moves a job to {@code ACT_RU_DEADLETTER_JOB} once its retries are exhausted. The row
 * keeps the process instance, the activity and the exception, which is enough to answer the two
 * questions an operator actually has: which order is stuck, and why.
 */
@Component
public class FlowableWorkflowAdmin implements WorkflowAdmin {

    private static final Logger log = LoggerFactory.getLogger(FlowableWorkflowAdmin.class);

    /** Retry budget granted when a human resubmits an item, after fixing whatever broke it. */
    private static final int RETRIES_ON_MANUAL_RESUBMIT = 3;

    private final ManagementService managementService;
    private final RuntimeService runtimeService;

    public FlowableWorkflowAdmin(ManagementService managementService, RuntimeService runtimeService) {
        this.managementService = managementService;
        this.runtimeService = runtimeService;
    }

    @Override
    public List<DeadLetterWork> deadLetterWork() {
        return managementService.createDeadLetterJobQuery()
                .orderByJobDuedate()
                .desc()
                .list()
                .stream()
                .map(this::toDeadLetterWork)
                .toList();
    }

    @Override
    public void retryDeadLetterWork(String workId) {
        Job job = managementService.createDeadLetterJobQuery().jobId(workId).singleResult();
        if (job == null) {
            throw new UnknownDeadLetterWorkException(workId);
        }

        log.info("Resubmitting dead letter job {} on activity {}", workId, job.getElementId());
        managementService.moveDeadLetterJobToExecutableJob(workId, RETRIES_ON_MANUAL_RESUBMIT);
    }

    private DeadLetterWork toDeadLetterWork(Job job) {
        return new DeadLetterWork(
                job.getId(),
                businessKeyOf(job.getProcessInstanceId()),
                job.getElementName() != null ? job.getElementName() : job.getElementId(),
                job.getExceptionMessage(),
                job.getDuedate() == null ? null : job.getDuedate().toInstant());
    }

    /** The business key is the order id, which is the only identifier an operator cares about. */
    private String businessKeyOf(String processInstanceId) {
        if (processInstanceId == null) {
            return null;
        }
        return runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .list()
                .stream()
                .findFirst()
                .map(instance -> instance.getBusinessKey())
                .orElse(null);
    }
}
