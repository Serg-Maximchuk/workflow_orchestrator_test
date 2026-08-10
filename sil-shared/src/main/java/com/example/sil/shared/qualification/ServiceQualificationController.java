package com.example.sil.shared.qualification;

import com.example.sil.shared.qualification.QualificationDtos.CheckServiceQualificationRequest;
import com.example.sil.shared.qualification.QualificationDtos.CheckServiceQualificationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** TMF645 Service Qualification. */
@RestController
@RequestMapping("/tmf-api/serviceQualification/v5")
@Tag(name = "Service Qualification (TMF645)",
        description = "Checks whether a service can be delivered at an address")
public class ServiceQualificationController {

    private final ServiceQualificationService service;

    public ServiceQualificationController(ServiceQualificationService service) {
        this.service = service;
    }

    @PostMapping("/checkServiceQualification")
    @Operation(summary = "Qualify a service at an address",
            description = "Calls the supplier and stores the answer. Safe to retry with the same "
                    + "Idempotency-Key: the first result is replayed instead of qualifying again.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Qualification performed or replayed"),
            @ApiResponse(responseCode = "409", description = "Idempotency-Key reused with a different body"),
            @ApiResponse(responseCode = "503", description = "Supplier unavailable after retries")
    })
    public ResponseEntity<CheckServiceQualificationResponse> checkServiceQualification(
            @Valid @RequestBody CheckServiceQualificationRequest request,
            @Parameter(description = "Makes the call safe to retry", example = "1f0a4b6e-...")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        CheckServiceQualificationResponse response = service.qualify(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/checkServiceQualification/{id}")
    @Operation(summary = "Retrieve a previous qualification")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "404", description = "Unknown qualification id")
    })
    public CheckServiceQualificationResponse getServiceQualification(@PathVariable String id) {
        return service.findById(id);
    }
}
