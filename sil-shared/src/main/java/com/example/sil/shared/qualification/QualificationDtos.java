package com.example.sil.shared.qualification;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Request and response shapes for TMF645 Service Qualification, trimmed to the fields this project
 * actually uses. Every deviation from the published TM Forum model is recorded in
 * {@code docs/variance-log.md} rather than left implicit.
 */
public final class QualificationDtos {

    private QualificationDtos() {}

    @Schema(name = "CheckServiceQualificationRequest",
            description = "Asks whether a service can be delivered at a given address")
    public record CheckServiceQualificationRequest(
            @Schema(description = "Caller's own identifier for this qualification", example = "OMS-4711")
            String externalId,

            @NotNull @Schema(description = "Where the service would be delivered")
            Place place,

            @NotBlank @Schema(description = "Service specification being qualified", example = "VOIP_BUSINESS")
            String serviceSpecId,

            @Min(1) @Schema(description = "Requested downstream speed in Mbps", example = "100")
            Integer requestedSpeedMbps) {}

    @Schema(name = "Place", description = "Delivery address, reduced to what qualification needs")
    public record Place(
            @NotBlank @Schema(example = "SW1A 1AA") String postcode,
            @Schema(example = "10 Downing Street") String streetAddress) {}

    @Schema(name = "CheckServiceQualificationResponse")
    public record CheckServiceQualificationResponse(
            @Schema(description = "Identifier assigned by this service") String id,
            String externalId,
            @Schema(description = "TMF task state", example = "done") String state,
            @Schema(description = "Whether the service can be delivered", example = "qualified")
            String qualificationResult,
            Integer maxSpeedMbps,
            Instant createdAt,
            @Schema(description = "Correlation id this qualification was handled under")
            String correlationId) {}
}
