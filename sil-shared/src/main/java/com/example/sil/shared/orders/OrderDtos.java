package com.example.sil.shared.orders;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * Request and response shapes for TMF641 Service Ordering, trimmed to what this project uses.
 * Deviations from the published TM Forum model are recorded in {@code docs/variance-log.md}.
 */
public final class OrderDtos {

    private OrderDtos() {}

    @Schema(name = "CreateServiceOrderRequest", description = "Orders a VOIP service for a customer")
    public record CreateServiceOrderRequest(
            @Schema(description = "Caller's own identifier for this order", example = "OMS-ORDER-88")
            String externalId,

            @NotNull @Valid Customer customer,

            @NotNull @Valid Place place,

            @NotBlank @Schema(example = "VOIP_BUSINESS") String serviceSpecId,

            @Min(1) @Schema(example = "100") Integer speedMbps) {}

    @Schema(name = "OrderCustomer")
    public record Customer(
            @NotBlank @Schema(example = "Acme Ltd") String name,
            @NotBlank @Email @Schema(example = "ops@acme.example") String email) {}

    @Schema(name = "OrderPlace")
    public record Place(
            @NotBlank @Schema(example = "SW1A 1AA") String postcode) {}

    @Schema(name = "ServiceOrderResponse")
    public record ServiceOrderResponse(
            String id,
            String externalId,
            @Schema(description = "TMF641 order state", example = "inProgress") String state,
            @Schema(description = "Provisioning references gathered so far") SupplierRefs supplierRefs,
            @Schema(description = "Why the order failed, when it did") String failureReason,
            String correlationId,
            Instant createdAt,
            Instant updatedAt) {}

    @Schema(name = "SupplierRefs",
            description = "Filled in one at a time as the workflow progresses; a null means that "
                    + "step has not completed yet")
    public record SupplierRefs(
            String customerId, String subscriptionId, String userId, String phoneNumber) {}

    @Schema(name = "OrderTimeline", description = "What the workflow has done so far")
    public record OrderTimelineResponse(String orderId, String state, List<TimelineStep> steps) {}

    @Schema(name = "TimelineStep")
    public record TimelineStep(String name, Instant startedAt, Instant endedAt, Long durationMillis) {}
}
