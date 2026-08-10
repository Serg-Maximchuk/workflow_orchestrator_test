package com.example.sil.shared.supplier;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter for the external VOIP and hardware supplier.
 *
 * <p>Phase 1 implements only the availability check behind Service Qualification. The six
 * provisioning operations from the specification (customer, subscription, user, number reservation,
 * number activation, hardware shipment) are added in Phase 2, when a workflow drives them.
 *
 * <p>Retry here is deliberately narrow: it covers the transient network and 5xx case, and it is
 * bounded. Once a workflow engine owns the call, this kind of in-process retry gets replaced by
 * engine-managed retry - the difference being that engine retries survive a restart of this
 * application, while a {@code @Retry} loop does not.
 */
@Component
public class VoipSupplierClient {

    private static final Logger log = LoggerFactory.getLogger(VoipSupplierClient.class);

    static final String RESILIENCE_NAME = "voipSupplier";

    private final RestClient restClient;

    public VoipSupplierClient(RestClient supplierRestClient) {
        this.restClient = supplierRestClient;
    }

    // The fallback sits on @Retry, not on @CircuitBreaker, and that ordering is load-bearing:
    // resilience4j applies retry as the outer aspect, so a fallback on the circuit breaker would
    // swallow the very first failure and hand back a non-retryable exception - the retries would
    // never happen. On @Retry the fallback runs only once the attempts are exhausted, or when the
    // breaker is open and rejects the call outright.
    @Retry(name = RESILIENCE_NAME, fallbackMethod = "availabilityUnavailable")
    @CircuitBreaker(name = RESILIENCE_NAME)
    public AvailabilityResponse checkAvailability(AvailabilityRequest request) {
        log.info("Checking supplier availability for postcode {}", request.postcode());
        return restClient.post()
                .uri("/supplier/v1/availability")
                .body(request)
                .retrieve()
                .body(AvailabilityResponse.class);
    }

    /**
     * Invoked by the circuit breaker when the supplier is failing consistently. Returning a
     * "cannot qualify right now" answer keeps our own API responsive instead of passing the
     * supplier's outage straight through to the caller.
     */
    @SuppressWarnings("unused") // referenced by name from @CircuitBreaker
    private AvailabilityResponse availabilityUnavailable(AvailabilityRequest request, Exception e) {
        log.warn("Supplier availability check failed for postcode {}: {}",
                request.postcode(), e.toString());
        throw new SupplierUnavailableException(
                "Supplier availability check is currently unavailable", e);
    }

    public record AvailabilityRequest(String postcode, String serviceSpecId, Integer requestedSpeedMbps) {}

    public record AvailabilityResponse(boolean available, List<String> offeredServiceSpecIds, Integer maxSpeedMbps) {}

    /** Raised when the supplier could not answer, after retries and the circuit breaker. */
    public static class SupplierUnavailableException extends RestClientException {
        public SupplierUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
