package com.example.sil.shared.orders;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the supplier reports outcomes it could not report synchronously.
 *
 * <p>The endpoint correlates on our order id, which was handed to the supplier as
 * {@code callbackCorrelationId} when activation was requested. Nothing here knows about process
 * instances or executions - that translation is the orchestrator's job, and keeping it there is
 * what lets a different engine answer the same callback.
 */
@RestController
@RequestMapping("/callbacks/voip")
@Tag(name = "Supplier callbacks", description = "Asynchronous outcomes reported by the supplier")
public class SupplierCallbackController {

    private final OrderOrchestrator orchestrator;

    public SupplierCallbackController(OrderOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/number-activation")
    @Operation(summary = "Report the outcome of a number activation",
            description = "Resumes the order that is waiting for this activation. A callback for "
                    + "an order that is not waiting - late, duplicate or unknown - is rejected "
                    + "rather than silently accepted.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Callback delivered to the waiting order"),
            @ApiResponse(responseCode = "409", description = "No order is waiting for this callback")
    })
    @Transactional
    public ResponseEntity<Void> numberActivation(@Valid @RequestBody NumberActivationCallback callback) {
        orchestrator.notifyNumberActivated(
                callback.orderId(), callback.activated(), callback.reason());
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /**
     * @param orderId the {@code callbackCorrelationId} we sent with the activation request
     * @param activated whether the number is now live
     * @param reason free text explaining a negative outcome
     */
    public record NumberActivationCallback(
            @NotBlank String orderId, boolean activated, String reason) {}
}
