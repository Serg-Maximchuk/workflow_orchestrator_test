package com.example.sil.orders;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Phase 2 acceptance tests: an order is fulfilled by a BPMN process rather than by a controller.
 */
class ServiceOrderWorkflowTest extends AbstractOrderWorkflowTest {

    @BeforeEach
    void resetSupplierAndEngine() {
        supplier.resetAll();
        stubHappyPathSupplier();
        deleteRunningProcessInstances();
        resetClock();
    }

    @Test
    @DisplayName("submitting an order returns immediately, before any supplier has been contacted")
    void submitReturnsBeforeAnySupplierCall() throws Exception {
        String orderId = submitOrder();

        // This is the property the async service tasks buy us: the caller's request is finished as
        // soon as the order is durable. The provisioning is queued work, not work the caller waits
        // for - and because it is queued in the database, it survives a restart from this moment on.
        assertThat(fetchOrder(orderId).read("$.state", String.class)).isEqualTo("inProgress");
        supplier.verify(exactly(0), postRequestedFor(urlPathEqualTo(CUSTOMERS_PATH)));
        assertThat(queuedJobCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("drives the supplier operations in order and completes the order")
    void fulfilsTheOrderThroughTheSupplierOperations() throws Exception {
        String orderId = submitOrder();

        executeAllJobs();
        completeActivationAndDelivery(orderId);

        var order = fetchOrder(orderId);
        assertThat(order.read("$.state", String.class)).isEqualTo("completed");
        assertThat(order.read("$.supplierRefs.customerId", String.class)).isEqualTo("cust-1");
        assertThat(order.read("$.supplierRefs.subscriptionId", String.class)).isEqualTo("sub-1");
        assertThat(order.read("$.supplierRefs.userId", String.class)).isEqualTo("user-1");
        assertThat(order.read("$.supplierRefs.phoneNumber", String.class)).isEqualTo("+442071234567");

        supplier.verify(exactly(1), postRequestedFor(urlPathEqualTo(CUSTOMERS_PATH)));
        supplier.verify(exactly(1), postRequestedFor(urlPathEqualTo(SUBSCRIPTIONS_PATH)));
        supplier.verify(exactly(1), postRequestedFor(urlPathEqualTo(USERS_PATH)));
        supplier.verify(exactly(1), postRequestedFor(urlPathEqualTo(NUMBERS_PATH)));

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(orderId).count())
                .as("a finished process leaves no runtime state behind")
                .isZero();
    }

    @Test
    @DisplayName("each step feeds the next: the subscription is created under the new customer")
    void passesSupplierReferencesFromStepToStep() throws Exception {
        submitOrder();

        executeAllJobs();

        supplier.verify(postRequestedFor(urlPathEqualTo(SUBSCRIPTIONS_PATH))
                .withRequestBody(matchingJsonPath("$.[?(@.customerId == 'cust-1')]")));
        supplier.verify(postRequestedFor(urlPathEqualTo(USERS_PATH))
                .withRequestBody(matchingJsonPath("$.[?(@.subscriptionId == 'sub-1')]")));
        supplier.verify(postRequestedFor(urlPathEqualTo(NUMBERS_PATH))
                .withRequestBody(matchingJsonPath("$.[?(@.userId == 'user-1')]")));
    }

    @Test
    @DisplayName("advances exactly one step per job")
    void oneJobAdvancesTheProcessByOneStep() throws Exception {
        String orderId = submitOrder();

        executeNextJob();
        assertThat(fetchOrder(orderId).read("$.supplierRefs.customerId", String.class))
                .isEqualTo("cust-1");
        assertThat(fetchOrder(orderId).read("$.supplierRefs.subscriptionId", String.class))
                .as("the subscription step must not have run yet")
                .isNull();

        executeNextJob();
        assertThat(fetchOrder(orderId).read("$.supplierRefs.subscriptionId", String.class))
                .isEqualTo("sub-1");
    }

    @Test
    @DisplayName("work already done survives a failure at a later step")
    void keepsCompletedWorkWhenALaterStepFails() throws Exception {
        supplier.stubFor(post(urlPathEqualTo(USERS_PATH)).willReturn(aResponse().withStatus(500)));

        String orderId = submitOrder();
        executeAllJobs();

        var order = fetchOrder(orderId);
        // Each async task committed its own transaction, so the customer and the subscription that
        // were genuinely created at the supplier are still recorded. Rolling them back locally
        // would be a lie: the remote side effect has happened and only a compensating call can
        // undo it, which is what Phase 4 adds.
        assertThat(order.read("$.supplierRefs.customerId", String.class)).isEqualTo("cust-1");
        assertThat(order.read("$.supplierRefs.subscriptionId", String.class)).isEqualTo("sub-1");
        assertThat(order.read("$.supplierRefs.userId", String.class)).isNull();

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(orderId).count())
                .as("the instance stays alive at the failed step, waiting to be retried")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the timeline reports the steps the workflow actually took")
    void reportsATimelineFromEngineHistory() throws Exception {
        String orderId = submitOrder();
        executeAllJobs();
        completeActivationAndDelivery(orderId);

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get(ORDERS_URL + "/" + orderId + "/timeline"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();

        var timeline = JsonPath.parse(result.getResponse().getContentAsString());
        List<String> steps = timeline.read("$.steps[*].name");

        // Ordering is asserted only up to the point where the test starts moving the clock.
        // advanceClockBy pins the engine clock to a fixed instant, so everything after it is
        // recorded with the same timestamp and no time-based ordering can separate those steps.
        // That is a property of time travel in the test, not of the endpoint.
        assertThat(steps).containsSubsequence(
                "Create customer", "Create subscription", "Create user",
                "Reserve phone number", "Request number activation",
                "Await activation callback", "Ship hardware");
        assertThat(steps).contains("Poll shipment status", "Complete order", "Order completed");
        assertThat(steps)
                .as("plumbing and never-fired boundary events are not steps a person wants to read")
                .doesNotContain("Delivered?", "Rejected by supplier", "Activation SLA breached",
                        "Remind customer", "waitStarted", "waitEnded");
        assertThat(timeline.read("$.state", String.class)).isEqualTo("completed");
        assertThat(timeline.read("$.steps[0].durationMillis", Object.class))
                .as("history records how long each step took")
                .isNotNull();
    }

    @Test
    @DisplayName("the correlation id reaches the supplier calls made on job threads")
    void propagatesCorrelationIdOntoJobThreads() throws Exception {
        String correlationId = "oms-" + UUID.randomUUID();

        mockMvc.perform(MockMvcRequestBuilders.post(ORDERS_URL)
                        .header("X-Correlation-Id", correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody("OMS-ORDER-1")))
                .andExpect(MockMvcResultMatchers.status().isCreated());

        executeAllJobs();

        // The supplier calls happen on job executor threads, long after the HTTP request finished.
        // Without restoring the MDC from a process variable, this header would be a fresh id and
        // the order journey would be untraceable exactly where it matters.
        supplier.verify(postRequestedFor(urlPathEqualTo(CUSTOMERS_PATH))
                .withHeader("X-Correlation-Id", equalTo(correlationId)));
        supplier.verify(postRequestedFor(urlPathEqualTo(NUMBERS_PATH))
                .withHeader("X-Correlation-Id", equalTo(correlationId)));
    }

    @Test
    @DisplayName("resubmitting with the same Idempotency-Key starts one workflow, not two")
    void idempotentSubmissionStartsOneWorkflow() throws Exception {
        String key = UUID.randomUUID().toString();

        String first = JsonPath.read(submit(orderBody("OMS-ORDER-2"), key), "$.id");
        String second = JsonPath.read(submit(orderBody("OMS-ORDER-2"), key), "$.id");

        assertThat(second).isEqualTo(first);
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(first).count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown order id is a 404 problem response")
    void unknownOrderIsNotFound() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(ORDERS_URL + "/so-nope"))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(MockMvcResultMatchers.jsonPath("$.title").value("Service order not found"));
    }

    private String submitOrder() throws Exception {
        return JsonPath.read(submit(orderBody("OMS-ORDER-" + UUID.randomUUID()), null), "$.id");
    }

    private String submit(String body, String idempotencyKey) throws Exception {
        var request = MockMvcRequestBuilders.post(ORDERS_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request)
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private com.jayway.jsonpath.DocumentContext fetchOrder(String orderId) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.get(ORDERS_URL + "/" + orderId))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.parse(body);
    }

    private static String orderBody(String externalId) {
        return """
                {"externalId":"%s",
                 "customer":{"name":"Acme Ltd","email":"ops@acme.example"},
                 "place":{"postcode":"SW1A 1AA"},
                 "serviceSpecId":"VOIP_BUSINESS",
                 "speedMbps":100}""".formatted(externalId);
    }
}
