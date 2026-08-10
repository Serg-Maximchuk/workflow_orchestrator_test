package com.example.sil.orders;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * The saga: when fulfilment cannot finish, everything it already did at the supplier is undone.
 *
 * <p>The assertion that matters here is not "compensation happened" but the <em>order</em> in which
 * it happened. Unwinding forwards would try to delete a customer while a subscription still hangs
 * off it, and a real supplier would refuse. Reverse order is the property, so reverse order is what
 * the tests read out of the stub's request journal.
 */
class CompensationTest extends AbstractOrderWorkflowTest {

    @BeforeEach
    void resetSupplierAndEngine() {
        supplier.resetAll();
        stubHappyPathSupplier();
        deleteRunningProcessInstances();
        resetClock();
    }

    @AfterEach
    void putTheClockBack() {
        resetClock();
    }

    @Test
    @DisplayName("a supplier rejection unwinds everything provisioned so far, newest first")
    void rejectionUnwindsInReverseOrder() throws Exception {
        supplier.stubFor(post(urlPathEqualTo(ACTIVATIONS_PATH))
                .willReturn(aResponse().withStatus(422).withBody("number already ported")));

        String orderId = submitOrder();
        executeAllJobs();

        assertThat(compensatingCallsInOrder())
                .as("undo has to run newest first, or the supplier refuses to delete a customer "
                        + "that still has a subscription")
                .containsExactly(
                        "/supplier/v1/numbers/reservations/+442071234567",
                        "/supplier/v1/users/user-1",
                        "/supplier/v1/subscriptions/sub-1",
                        "/supplier/v1/customers/cust-1");

        DocumentContext order = fetchOrder(orderId);
        assertThat(order.read("$.state", String.class)).isEqualTo("failed");
        assertThat(order.read("$.failureReason", String.class)).contains("rejected the activation");
        // The references are cleared because those resources no longer exist at the supplier.
        assertThat(order.read("$.supplierRefs.customerId", String.class)).isNull();
        assertThat(order.read("$.supplierRefs.subscriptionId", String.class)).isNull();
        assertThat(order.read("$.supplierRefs.userId", String.class)).isNull();
        assertThat(order.read("$.supplierRefs.phoneNumber", String.class)).isNull();
    }

    @Test
    @DisplayName("the activation request itself is compensated when the SLA expires")
    void slaBreachAlsoCancelsTheActivation() throws Exception {
        String orderId = submitOrder();
        executeAllJobs();

        // The supplier accepted the activation and then went quiet. Unlike a rejection, the
        // activation step did complete, so it has something to undo of its own.
        advanceClockBy(Duration.ofHours(3));
        executeAllJobs();

        assertThat(compensatingCallsInOrder()).containsExactly(
                "/supplier/v1/numbers/activations/act-1",
                "/supplier/v1/numbers/reservations/+442071234567",
                "/supplier/v1/users/user-1",
                "/supplier/v1/subscriptions/sub-1",
                "/supplier/v1/customers/cust-1");

        assertThat(fetchOrder(orderId).read("$.state", String.class)).isEqualTo("failed");
    }

    @Test
    @DisplayName("cancelling an order in flight undoes everything, including the shipment")
    void clientCancellationUnwindsTheWholeOrder() throws Exception {
        String orderId = submitOrder();
        executeAllJobs();
        completeActivationOnly(orderId);

        // The handset is on its way and the process is asleep on the poll timer. The client
        // changes their mind now, which is the case a hand-written rollback always gets wrong:
        // the furthest step reached is not the step that failed.
        mockMvc.perform(MockMvcRequestBuilders.post(ORDERS_URL + "/" + orderId + "/cancel"))
                .andExpect(MockMvcResultMatchers.status().isAccepted());
        executeAllJobs();

        assertThat(compensatingCallsInOrder()).containsExactly(
                "/supplier/v1/shipments/ship-1",
                "/supplier/v1/numbers/activations/act-1",
                "/supplier/v1/numbers/reservations/+442071234567",
                "/supplier/v1/users/user-1",
                "/supplier/v1/subscriptions/sub-1",
                "/supplier/v1/customers/cust-1");

        DocumentContext order = fetchOrder(orderId);
        assertThat(order.read("$.state", String.class))
                .as("cancelled, not failed - the client asked for this")
                .isEqualTo("cancelled");
        assertThat(order.read("$.supplierRefs.customerId", String.class)).isNull();

        assertThat(timerJobCount())
                .as("the poll timer must die with the transaction it belonged to")
                .isZero();
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(orderId).count())
                .isZero();
    }

    @Test
    @DisplayName("a completed order compensates nothing and cannot be cancelled")
    void completedOrdersAreNotUnwound() throws Exception {
        String orderId = submitOrder();
        executeAllJobs();
        completeActivationAndDelivery(orderId);

        assertThat(fetchOrder(orderId).read("$.state", String.class)).isEqualTo("completed");
        assertThat(compensatingCallsInOrder())
                .as("a successful order must never undo itself")
                .isEmpty();

        mockMvc.perform(MockMvcRequestBuilders.post(ORDERS_URL + "/" + orderId + "/cancel"))
                .andExpect(MockMvcResultMatchers.status().isConflict())
                .andExpect(MockMvcResultMatchers.jsonPath("$.title").value("Order cannot be cancelled"));
    }

    @Test
    @DisplayName("cancelling mid-provisioning is accepted and unwinds at the next checkpoint")
    void cancellingMidProvisioningUnwindsAtTheNextCheckpoint() throws Exception {
        String orderId = submitOrder();
        executeNextJob();
        executeNextJob();

        // Two steps in, and the process is between supplier calls rather than parked on a wait -
        // there is no engine subscription to deliver a cancellation to. It is still accepted,
        // because the intent goes on the order and the checkpoint after the next step reads it.
        mockMvc.perform(MockMvcRequestBuilders.post(ORDERS_URL + "/" + orderId + "/cancel"))
                .andExpect(MockMvcResultMatchers.status().isAccepted());

        executeAllJobs();

        assertThat(compensatingCallsInOrder())
                .as("everything provisioned is undone, newest first, and nothing else is")
                .containsExactly(
                        "/supplier/v1/subscriptions/sub-1",
                        "/supplier/v1/customers/cust-1");
        assertThat(fetchOrder(orderId).read("$.state", String.class)).isEqualTo("cancelled");

        // The guarantee worth having: once the cancellation is recorded, no further supplier call
        // is made. The next step still runs as a token-carrier - something has to reach the
        // checkpoint that routes into the unwind - but it does not provision anything, so there is
        // nothing extra to undo.
        supplier.verify(exactly(0), postRequestedFor(urlPathEqualTo(USERS_PATH)));
        supplier.verify(exactly(0), postRequestedFor(urlPathEqualTo(NUMBERS_PATH)));
    }

    @Test
    @DisplayName("cancelling before any step ran touches the supplier not at all")
    void cancellingBeforeAnyStepTouchesNothing() throws Exception {
        String orderId = submitOrder();

        mockMvc.perform(MockMvcRequestBuilders.post(ORDERS_URL + "/" + orderId + "/cancel"))
                .andExpect(MockMvcResultMatchers.status().isAccepted());
        executeAllJobs();

        assertThat(fetchOrder(orderId).read("$.state", String.class)).isEqualTo("cancelled");
        assertThat(compensatingCallsInOrder())
                .as("nothing was provisioned, so there is nothing to undo")
                .isEmpty();
        supplier.verify(exactly(0), postRequestedFor(urlPathEqualTo(CUSTOMERS_PATH)));
    }

    @Test
    @DisplayName("cancelling an order that already finished is a 409")
    void cancellingFinishedOrderIsRejected() throws Exception {
        String orderId = submitOrder();
        executeAllJobs();
        completeActivationAndDelivery(orderId);

        mockMvc.perform(MockMvcRequestBuilders.post(ORDERS_URL + "/" + orderId + "/cancel"))
                .andExpect(MockMvcResultMatchers.status().isConflict())
                .andExpect(MockMvcResultMatchers.jsonPath("$.title").value("Order cannot be cancelled"));
    }

    @Test
    @DisplayName("cancelling while waiting for the activation callback unwinds what exists")
    void cancellingDuringActivationWaitUnwinds() throws Exception {
        String orderId = submitOrder();
        executeAllJobs();

        mockMvc.perform(MockMvcRequestBuilders.post(ORDERS_URL + "/" + orderId + "/cancel"))
                .andExpect(MockMvcResultMatchers.status().isAccepted());
        executeAllJobs();

        assertThat(compensatingCallsInOrder()).containsExactly(
                "/supplier/v1/numbers/activations/act-1",
                "/supplier/v1/numbers/reservations/+442071234567",
                "/supplier/v1/users/user-1",
                "/supplier/v1/subscriptions/sub-1",
                "/supplier/v1/customers/cust-1");
        assertThat(fetchOrder(orderId).read("$.state", String.class)).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("cancelling an unknown order is a 404")
    void cancellingUnknownOrderIsNotFound() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post(ORDERS_URL + "/so-nope/cancel"))
                .andExpect(MockMvcResultMatchers.status().isNotFound());
    }

    /** Paths of the DELETE calls the supplier received, oldest first. */
    private List<String> compensatingCallsInOrder() {
        return supplier.findAll(com.github.tomakehurst.wiremock.client.WireMock
                        .anyRequestedFor(urlPathMatching("/supplier/.*")))
                .stream()
                .filter(request -> "DELETE".equals(request.getMethod().getName()))
                .sorted(Comparator.comparing(LoggedRequest::getLoggedDate))
                // Decoded, so the expectations below can read as the phone number rather than as
                // its percent-encoding.
                .map(request -> java.net.URLDecoder.decode(
                        request.getUrl(), java.nio.charset.StandardCharsets.UTF_8))
                .toList();
    }


    /** Delivers the activation callback and lets the shipment request go out, nothing more. */
    private void completeActivationOnly(String orderId) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/callbacks/voip/number-activation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"%s","activated":true,"reason":""}""".formatted(orderId)))
                .andExpect(MockMvcResultMatchers.status().isAccepted());
        executeAllJobs();
    }

    private String submitOrder() throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.post(ORDERS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalId":"OMS-%s",
                                 "customer":{"name":"Acme Ltd","email":"ops@acme.example"},
                                 "place":{"postcode":"SW1A 1AA"},
                                 "serviceSpecId":"VOIP_BUSINESS",
                                 "speedMbps":100}""".formatted(UUID.randomUUID())))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private DocumentContext fetchOrder(String orderId) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.get(ORDERS_URL + "/" + orderId))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.parse(body);
    }
}
