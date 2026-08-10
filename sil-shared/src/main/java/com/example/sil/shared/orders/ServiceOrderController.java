package com.example.sil.shared.orders;

import com.example.sil.shared.orders.OrderDtos.CreateServiceOrderRequest;
import com.example.sil.shared.orders.OrderDtos.OrderTimelineResponse;
import com.example.sil.shared.orders.OrderDtos.ServiceOrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** TMF641 Service Ordering. */
@RestController
@RequestMapping("/tmf-api/serviceOrdering/v4/serviceOrder")
@Tag(name = "Service Ordering (TMF641)",
        description = "Submits VOIP orders and reports how far their fulfilment has got")
public class ServiceOrderController {

    private final ServiceOrderService service;

    public ServiceOrderController(ServiceOrderService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Submit a service order",
            description = "Stores the order and starts its fulfilment workflow, then returns "
                    + "immediately. Supplier provisioning happens asynchronously - poll the order "
                    + "or its timeline to follow progress.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order accepted and fulfilment started"),
            @ApiResponse(responseCode = "409", description = "Idempotency-Key reused with a different body")
    })
    public ResponseEntity<ServiceOrderResponse> submit(
            @Valid @RequestBody CreateServiceOrderRequest request,
            @Parameter(description = "Makes the submission safe to retry")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        return ResponseEntity.status(HttpStatus.CREATED).body(service.submit(request, idempotencyKey));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Retrieve an order and the supplier references gathered so far")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "404", description = "Unknown order id")
    })
    public ServiceOrderResponse get(@PathVariable String id) {
        return service.findById(id);
    }

    @GetMapping
    @Operation(summary = "List orders in a given state")
    public List<ServiceOrderResponse> list(@RequestParam ServiceOrderState state) {
        return service.findByState(state);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order and undo what was already provisioned",
            description = "Returns immediately. Compensation is itself a series of supplier calls, "
                    + "so the order reaches the cancelled state only once they have all succeeded - "
                    + "poll the order to see when.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Cancellation accepted, unwinding started"),
            @ApiResponse(responseCode = "404", description = "Unknown order id"),
            @ApiResponse(responseCode = "409", description = "Fulfilment is no longer running")
    })
    public ResponseEntity<ServiceOrderResponse> cancel(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.requestCancellation(id));
    }

    @GetMapping("/{id}/timeline")
    @Operation(summary = "Show what the fulfilment workflow has done so far",
            description = "Read from the engine's own history, so it reports the steps actually "
                    + "taken and how long each one took.")
    public OrderTimelineResponse timeline(@PathVariable String id) {
        return service.timelineOf(id);
    }
}
