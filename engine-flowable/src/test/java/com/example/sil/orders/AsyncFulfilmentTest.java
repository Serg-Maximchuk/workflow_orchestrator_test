package com.example.sil.orders;

import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * The asynchronous half of fulfilment: waiting for a callback, waiting on timers, and polling.
 *
 * <p>Not one of these tests sleeps. Time is moved by setting the engine clock, which is what makes
 * an SLA measured in hours and a shipment measured in weeks testable in milliseconds - and testable
 * repeatably, rather than "usually, on a fast machine".
 */
class AsyncFulfilmentTest extends AbstractOrderWorkflowTest {

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
    @DisplayName("parks the order on a message and calls nothing until the callback arrives")
    void waitsForTheActivationCallback() throws Exception {
        String orderId = submitOrder();
        driveUntilWaitingForCallback();

        assertThat(fetchOrder(orderId).read("$.supplierRefs.phoneNumber", String.class))
                .isEqualTo("+442071234567");
        supplier.verify(exactly(1), postRequestedFor(urlPathEqualTo(ACTIVATIONS_PATH)));
        // The process is parked. It occupies no thread and no memory - just a subscription row.
        assertThat(queuedJobCount()).isZero();
        supplier.verify(exactly(0), postRequestedFor(urlPathEqualTo(SHIPMENTS_PATH)));

        // The supplier was told which order to quote back to us.
        supplier.verify(postRequestedFor(urlPathEqualTo(ACTIVATIONS_PATH))
                .withRequestBody(matchingJsonPath("$.[?(@.callbackCorrelationId == '" + orderId + "')]")));
    }

    @Test
    @DisplayName("the callback resumes the order and fulfilment runs on to completion")
    void callbackResumesTheOrder() throws Exception {
        String orderId = submitOrder();
        driveUntilWaitingForCallback();

        postCallback(orderId, true).andExpect(MockMvcResultMatchers.status().isAccepted());
        executeAllJobs();

        // Shipment is behind a timer, so nothing more happens until the clock moves.
        assertThat(fetchOrder(orderId).read("$.state", String.class)).isEqualTo("inProgress");
        supplier.verify(exactly(1), postRequestedFor(urlPathEqualTo(SHIPMENTS_PATH)));

        advanceClockBy(Duration.ofHours(7));
        executeAllJobs();

        DocumentContext order = fetchOrder(orderId);
        assertThat(order.read("$.state", String.class)).isEqualTo("completed");
        assertThat(order.read("$.fulfilment.shipmentStatus", String.class)).isEqualTo("delivered");
    }

    @Test
    @DisplayName("a duplicate callback is rejected instead of disturbing an order that moved on")
    void duplicateCallbackIsRejected() throws Exception {
        String orderId = submitOrder();
        driveUntilWaitingForCallback();

        postCallback(orderId, true).andExpect(MockMvcResultMatchers.status().isAccepted());

        // At-least-once delivery is the norm for supplier callbacks. The second one must not
        // resume anything: the subscription is gone, so there is nothing to correlate against.
        postCallback(orderId, true)
                .andExpect(MockMvcResultMatchers.status().isConflict())
                .andExpect(MockMvcResultMatchers.jsonPath("$.title")
                        .value("No order waiting for this callback"));
    }

    @Test
    @DisplayName("a callback for an unknown order is rejected")
    void unknownCallbackIsRejected() throws Exception {
        postCallback("so-not-a-real-order", true)
                .andExpect(MockMvcResultMatchers.status().isConflict());
    }

    @Test
    @DisplayName("the SLA timer fails an order the supplier never confirmed")
    void activationSlaBreachFailsTheOrder() throws Exception {
        String orderId = submitOrder();
        driveUntilWaitingForCallback();

        // The supplier has two hours. It stays silent.
        advanceClockBy(Duration.ofHours(3));
        executeAllJobs();

        DocumentContext order = fetchOrder(orderId);
        assertThat(order.read("$.state", String.class)).isEqualTo("failed");
        assertThat(order.read("$.failureReason", String.class))
                .contains("did not confirm number activation");

        // A breached SLA must stop the journey, not let it drift on to shipping hardware for a
        // service that was never activated.
        supplier.verify(exactly(0), postRequestedFor(urlPathEqualTo(SHIPMENTS_PATH)));
    }

    @Test
    @DisplayName("the repeating reminder timer fires without taking the order off its wait")
    void nonInterruptingReminderKeepsTheOrderWaiting() throws Exception {
        String orderId = submitOrder();
        driveUntilWaitingForCallback();

        advanceClockBy(Duration.ofMinutes(31));
        executeAllJobs();

        assertThat(fetchOrder(orderId).read("$.fulfilment.remindersSent", Integer.class))
                .as("the reminder fired")
                .isEqualTo(1);

        // cancelActivity="false": the token is still on the wait, so the callback can still arrive.
        postCallback(orderId, true).andExpect(MockMvcResultMatchers.status().isAccepted());
    }

    @Test
    @DisplayName("polls the shipment on a timer until it is delivered")
    void pollsTheShipmentUntilDelivered() throws Exception {
        String orderId = submitOrder();
        driveUntilWaitingForCallback();
        postCallback(orderId, true);
        executeAllJobs();

        stubShipmentStatus("in_transit");

        // Two laps of the loop with the handset still in transit.
        for (int lap = 0; lap < 2; lap++) {
            advanceClockBy(Duration.ofHours(7));
            executeAllJobs();
        }
        DocumentContext inTransit = fetchOrder(orderId);
        assertThat(inTransit.read("$.state", String.class)).isEqualTo("inProgress");
        assertThat(inTransit.read("$.fulfilment.shipmentPollCount", Integer.class)).isEqualTo(2);
        assertThat(timerJobCount())
                .as("the process is asleep on the next poll timer, costing nothing meanwhile")
                .isEqualTo(1);

        stubShipmentStatus("delivered");
        advanceClockBy(Duration.ofHours(7));
        executeAllJobs();

        assertThat(fetchOrder(orderId).read("$.state", String.class)).isEqualTo("completed");
        supplier.verify(exactly(3), getRequestedFor(urlPathMatching(SHIPMENTS_PATH + "/.*")));
    }

    private void driveUntilWaitingForCallback() {
        executeAllJobs();
    }

    private org.springframework.test.web.servlet.ResultActions postCallback(
            String orderId, boolean activated) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/callbacks/voip/number-activation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderId":"%s","activated":%s,"reason":""}"""
                        .formatted(orderId, activated)));
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
