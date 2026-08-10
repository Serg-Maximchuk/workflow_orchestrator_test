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
 * <p>Covers all six provisioning operations. The last two are the interesting ones: number
 * activation answers "accepted" and reports the real outcome later through a callback, and hardware
 * shipment takes weeks and has to be polled. Neither fits a request/response call, which is exactly
 * why the workflow engine earns its place.
 *
 * <p>Note what is <em>not</em> here: retry on the provisioning calls. The availability check keeps
 * its {@code @Retry} because it is called synchronously from an HTTP request with a caller waiting.
 * The provisioning calls are driven by the workflow engine, and retrying them in-process would be
 * strictly worse than letting the engine do it: an engine retry is a row in the database, so it
 * survives a restart of this application, while a {@code @Retry} loop dies with the thread.
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

    /** Supplier operation 1 of 6: register the customer. */
    public CustomerResponse createCustomer(CreateCustomerRequest request) {
        log.info("Creating supplier customer for {}", request.externalId());
        return restClient.post()
                .uri("/supplier/v1/customers")
                .body(request)
                .retrieve()
                .body(CustomerResponse.class);
    }

    /** Supplier operation 2 of 6: open a subscription under an existing customer. */
    public SubscriptionResponse createSubscription(CreateSubscriptionRequest request) {
        log.info("Creating supplier subscription for customer {}", request.customerId());
        return restClient.post()
                .uri("/supplier/v1/subscriptions")
                .body(request)
                .retrieve()
                .body(SubscriptionResponse.class);
    }

    /** Supplier operation 3 of 6: create the end user on the subscription. */
    public UserResponse createUser(CreateUserRequest request) {
        log.info("Creating supplier user on subscription {}", request.subscriptionId());
        return restClient.post()
                .uri("/supplier/v1/users")
                .body(request)
                .retrieve()
                .body(UserResponse.class);
    }

    /** Supplier operation 4 of 6: reserve a phone number for the user. */
    public NumberReservationResponse reserveNumber(ReserveNumberRequest request) {
        log.info("Reserving a number for user {}", request.userId());
        return restClient.post()
                .uri("/supplier/v1/numbers/reservations")
                .body(request)
                .retrieve()
                .body(NumberReservationResponse.class);
    }

    /**
     * Supplier operation 5 of 6: request activation of the reserved number.
     *
     * <p>The supplier answers "accepted" and nothing more - porting a number involves the losing
     * carrier and can take hours or days. The real outcome arrives later on our callback endpoint,
     * which is why the process waits on a message rather than on this call returning.
     */
    public ActivationAcceptedResponse activateNumber(ActivateNumberRequest request) {
        log.info("Requesting activation of {} for user {}", request.phoneNumber(), request.userId());
        return restClient.post()
                .uri("/supplier/v1/numbers/activations")
                .body(request)
                .retrieve()
                .body(ActivationAcceptedResponse.class);
    }

    /** Supplier operation 6 of 6: ship the handset. */
    public ShipmentResponse shipHardware(ShipHardwareRequest request) {
        log.info("Requesting hardware shipment for order {}", request.orderReference());
        return restClient.post()
                .uri("/supplier/v1/shipments")
                .body(request)
                .retrieve()
                .body(ShipmentResponse.class);
    }

    /**
     * Polls a shipment. The supplier offers no callback for this one, so the process has to ask -
     * on a timer, for as long as it takes.
     */
    public ShipmentStatusResponse getShipmentStatus(String shipmentId) {
        log.info("Polling shipment {}", shipmentId);
        return restClient.get()
                .uri("/supplier/v1/shipments/{shipmentId}", shipmentId)
                .retrieve()
                .body(ShipmentStatusResponse.class);
    }

    /**
     * Invoked when the supplier is failing consistently. Returning a "cannot qualify right now"
     * answer keeps our own API responsive instead of passing the supplier's outage straight
     * through to the caller.
     */
    @SuppressWarnings("unused") // referenced by name from @Retry
    private AvailabilityResponse availabilityUnavailable(AvailabilityRequest request, Exception e) {
        log.warn("Supplier availability check failed for postcode {}: {}",
                request.postcode(), e.toString());
        throw new SupplierUnavailableException(
                "Supplier availability check is currently unavailable", e);
    }

    public record AvailabilityRequest(String postcode, String serviceSpecId, Integer requestedSpeedMbps) {}

    public record AvailabilityResponse(boolean available, List<String> offeredServiceSpecIds, Integer maxSpeedMbps) {}

    public record CreateCustomerRequest(String externalId, String name, String email, String postcode) {}

    public record CustomerResponse(String customerId) {}

    public record CreateSubscriptionRequest(String customerId, String serviceSpecId, Integer speedMbps) {}

    public record SubscriptionResponse(String subscriptionId) {}

    public record CreateUserRequest(String subscriptionId, String displayName, String email) {}

    public record UserResponse(String userId) {}

    public record ReserveNumberRequest(String userId, String areaCode) {}

    public record NumberReservationResponse(String phoneNumber, String reservationId) {}

    public record ActivateNumberRequest(String userId, String phoneNumber, String callbackCorrelationId) {}

    public record ActivationAcceptedResponse(String activationId, String status) {}

    public record ShipHardwareRequest(String orderReference, String postcode, String hardwareType) {}

    public record ShipmentResponse(String shipmentId) {}

    public record ShipmentStatusResponse(String shipmentId, String status) {}

    /** Raised when the supplier could not answer, after retries and the circuit breaker. */
    public static class SupplierUnavailableException extends RestClientException {
        public SupplierUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
