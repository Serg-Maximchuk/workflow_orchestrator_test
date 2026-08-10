package com.example.sil.shared.qualification;

import com.example.sil.shared.correlation.CorrelationContext;
import com.example.sil.shared.idempotency.IdempotencyService;
import com.example.sil.shared.qualification.QualificationDtos.CheckServiceQualificationRequest;
import com.example.sil.shared.qualification.QualificationDtos.CheckServiceQualificationResponse;
import com.example.sil.shared.supplier.VoipSupplierClient;
import com.example.sil.shared.supplier.VoipSupplierClient.AvailabilityRequest;
import com.example.sil.shared.supplier.VoipSupplierClient.AvailabilityResponse;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TMF645 Service Qualification: ask the supplier whether a service can be delivered at an address,
 * and remember the answer.
 *
 * <p>This is the one journey in the project that is deliberately synchronous and process-free. It
 * establishes the cross-cutting concerns - idempotency, correlation, timeouts, retry - on a request
 * simple enough that they can be seen in isolation, before a workflow engine is introduced in
 * Phase 2 and starts owning the retrying and the state.
 */
@Service
public class ServiceQualificationService {

    static final String QUALIFIED = "qualified";
    static final String UNQUALIFIED = "unqualified";

    private final VoipSupplierClient supplierClient;
    private final ServiceQualificationRepository repository;
    private final IdempotencyService idempotencyService;

    public ServiceQualificationService(
            VoipSupplierClient supplierClient,
            ServiceQualificationRepository repository,
            IdempotencyService idempotencyService) {
        this.supplierClient = supplierClient;
        this.repository = repository;
        this.idempotencyService = idempotencyService;
    }

    @Transactional
    public CheckServiceQualificationResponse qualify(
            CheckServiceQualificationRequest request, String idempotencyKey) {

        String qualificationId = idempotencyService
                .execute(idempotencyKey, fingerprintOf(request), () -> callSupplierAndStore(request))
                .resourceId();

        return repository.findById(qualificationId)
                .map(ServiceQualificationService::toResponse)
                .orElseThrow(() -> new IllegalStateException(
                        "Qualification " + qualificationId + " vanished after being stored"));
    }

    @Transactional(readOnly = true)
    public CheckServiceQualificationResponse findById(String id) {
        return repository.findById(id)
                .map(ServiceQualificationService::toResponse)
                .orElseThrow(() -> new QualificationNotFoundException(id));
    }

    private String callSupplierAndStore(CheckServiceQualificationRequest request) {
        AvailabilityResponse availability = supplierClient.checkAvailability(new AvailabilityRequest(
                request.place().postcode(), request.serviceSpecId(), request.requestedSpeedMbps()));

        String result = availability.available() ? QUALIFIED : UNQUALIFIED;
        ServiceQualification stored = repository.save(new ServiceQualification(
                "sq-" + UUID.randomUUID(),
                request.externalId(),
                request.place().postcode(),
                request.serviceSpecId(),
                result,
                availability.maxSpeedMbps(),
                CorrelationContext.currentOrNew()));

        return stored.getId();
    }

    /**
     * Canonical form of the request used to detect idempotency key reuse. Built from the fields
     * that decide the outcome, so a caller retrying the same question is recognised even if the
     * JSON is formatted differently.
     */
    private String fingerprintOf(CheckServiceQualificationRequest request) {
        return String.join("|",
                String.valueOf(request.externalId()),
                request.place().postcode(),
                request.serviceSpecId(),
                String.valueOf(request.requestedSpeedMbps()));
    }

    private static CheckServiceQualificationResponse toResponse(ServiceQualification entity) {
        return new CheckServiceQualificationResponse(
                entity.getId(),
                entity.getExternalId(),
                "done",
                entity.getQualificationResult(),
                entity.getMaxSpeedMbps(),
                entity.getCreatedAt(),
                entity.getCorrelationId());
    }

    /** Raised when a qualification id is unknown, mapped to 404 by the API error handler. */
    public static class QualificationNotFoundException extends RuntimeException {
        public QualificationNotFoundException(String id) {
            super("No service qualification with id " + id);
        }
    }
}
