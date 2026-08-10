package com.example.sil.shared.orders;

import com.example.sil.shared.orders.WorkflowAdmin.DeadLetterWork;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operations endpoints for work the engine gave up on. Deliberately outside the TMF surface: this
 * is for whoever is on support, not for the client's order management system.
 */
@RestController
@RequestMapping("/admin/workflow")
@Tag(name = "Workflow operations", description = "Inspect and resubmit work that exhausted its retries")
public class WorkflowAdminController {

    private final WorkflowAdmin workflowAdmin;

    public WorkflowAdminController(WorkflowAdmin workflowAdmin) {
        this.workflowAdmin = workflowAdmin;
    }

    @GetMapping("/dead-letter")
    @Operation(summary = "List orders stuck at a step that exhausted its retries")
    public List<DeadLetterWork> deadLetterWork() {
        return workflowAdmin.deadLetterWork();
    }

    @PostMapping("/dead-letter/{workId}/retry")
    @Operation(summary = "Resubmit a stuck step with a fresh retry budget",
            description = "Use after fixing the cause. The order resumes from the failed step, "
                    + "so the work already done is not repeated.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Resubmitted"),
            @ApiResponse(responseCode = "404", description = "Unknown or already retried")
    })
    public ResponseEntity<Void> retry(@PathVariable String workId) {
        workflowAdmin.retryDeadLetterWork(workId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
