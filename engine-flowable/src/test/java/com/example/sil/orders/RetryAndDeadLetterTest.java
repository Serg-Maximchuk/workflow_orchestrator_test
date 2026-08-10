package com.example.sil.orders;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.flowable.job.api.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * What happens when the supplier misbehaves: retries, the dead letter queue, and the difference
 * between "try again" and "this will never work".
 *
 * <p>This is where the engine earns its keep over a retry annotation. Every retry here is a row in
 * the database with a due date and a remaining count. Nothing is held in memory, so a redeploy in
 * the middle of a backoff loses nothing, and an order that runs out of attempts is parked somewhere
 * a human can find it rather than vanishing into a log file.
 */
class RetryAndDeadLetterTest extends AbstractOrderWorkflowTest {

    @BeforeEach
    void resetSupplierAndEngine() {
        supplier.resetAll();
        stubHappyPathSupplier();
        deleteRunningProcessInstances();
        clearDeadLetterJobs();
        resetClock();
    }

    @AfterEach
    void putTheClockBack() {
        resetClock();
    }

    @Test
    @DisplayName("retries a transient supplier failure and carries on")
    void retriesTransientFailures() throws Exception {
        supplier.stubFor(post(urlPathEqualTo(CUSTOMERS_PATH))
                .inScenario("flaky customer")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("recovered"));
        supplier.stubFor(post(urlPathEqualTo(CUSTOMERS_PATH))
                .inScenario("flaky customer")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson("{\"customerId\":\"cust-1\"}")));

        String orderId = submitOrder();

        // First attempt fails. The job is not lost: it goes back with one attempt fewer and a due
        // date ten seconds out.
        executeAllJobsIgnoringFailures();
        assertThat(fetchOrderField(orderId, "$.supplierRefs.customerId")).isNull();
        assertThat(retriesLeftOnNextJob())
                .as("a failed attempt spends one retry rather than failing the order")
                .isEqualTo(2);

        // Ten seconds pass and the retry runs.
        advanceClockBy(Duration.ofSeconds(11));
        executeAllJobs();

        assertThat(fetchOrderField(orderId, "$.supplierRefs.customerId")).isEqualTo("cust-1");
        supplier.verify(exactly(2), postRequestedFor(urlPathEqualTo(CUSTOMERS_PATH)));
    }

    @Test
    @DisplayName("a permanently broken step ends up in the dead letter queue, not lost")
    void exhaustedRetriesLandInTheDeadLetterQueue() throws Exception {
        supplier.stubFor(post(urlPathEqualTo(CUSTOMERS_PATH))
                .willReturn(aResponse().withStatus(500)));

        String orderId = submitOrder();
        exhaustRetries();

        assertThat(deadLetterJobCount()).isEqualTo(1);

        MvcResult listing = mockMvc.perform(MockMvcRequestBuilders.get("/admin/workflow/dead-letter"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();

        var body = JsonPath.parse(listing.getResponse().getContentAsString());
        assertThat(body.read("$[0].orderId", String.class))
                .as("an operator needs the order id, not a job id")
                .isEqualTo(orderId);
        assertThat(body.read("$[0].stepName", String.class)).isEqualTo("Create customer");
        assertThat(body.read("$[0].errorMessage", String.class)).isNotBlank();

        // The order is parked, not failed and not half-applied: its state is intact and the journey
        // can continue from exactly this step once someone fixes the cause.
        assertThat(fetchOrderField(orderId, "$.state")).isEqualTo("inProgress");
    }

    @Test
    @DisplayName("resubmitting dead letter work resumes the order from the failed step")
    void resubmittingDeadLetterWorkResumesTheOrder() throws Exception {
        supplier.stubFor(post(urlPathEqualTo(CUSTOMERS_PATH))
                .willReturn(aResponse().withStatus(500)));

        String orderId = submitOrder();
        exhaustRetries();

        String workId = JsonPath.read(
                mockMvc.perform(MockMvcRequestBuilders.get("/admin/workflow/dead-letter"))
                        .andReturn().getResponse().getContentAsString(),
                "$[0].workId");

        // Whatever broke it is now fixed.
        supplier.stubFor(post(urlPathEqualTo(CUSTOMERS_PATH))
                .willReturn(okJson("{\"customerId\":\"cust-1\"}")));

        mockMvc.perform(MockMvcRequestBuilders.post("/admin/workflow/dead-letter/" + workId + "/retry"))
                .andExpect(MockMvcResultMatchers.status().isAccepted());

        assertThat(deadLetterJobCount()).isZero();
        executeAllJobs();

        // It picked up where it stopped: the customer is created and the journey moved on to the
        // steps after it. Nothing before the failure was repeated.
        assertThat(fetchOrderField(orderId, "$.supplierRefs.customerId")).isEqualTo("cust-1");
        assertThat(fetchOrderField(orderId, "$.supplierRefs.phoneNumber")).isEqualTo("+442071234567");
    }

    @Test
    @DisplayName("retrying unknown dead letter work is a 404")
    void retryingUnknownWorkIsNotFound() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/admin/workflow/dead-letter/not-a-job/retry"))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(MockMvcResultMatchers.jsonPath("$.title").value("Dead letter work not found"));
    }

    @Test
    @DisplayName("a supplier rejection fails the order immediately instead of being retried")
    void businessRejectionIsNotRetried() throws Exception {
        // 422: the number is already ported. No amount of retrying changes that answer.
        supplier.stubFor(post(urlPathEqualTo(ACTIVATIONS_PATH))
                .willReturn(aResponse().withStatus(422).withBody("number already ported")));

        String orderId = submitOrder();
        executeAllJobs();

        assertThat(fetchOrderField(orderId, "$.state")).isEqualTo("failed");
        assertThat(fetchOrderField(orderId, "$.failureReason")).contains("rejected the activation");

        // The heart of the distinction: one call, no retries, no dead letter. The process took its
        // rejection path because the delegate raised a BPMN error rather than an exception.
        supplier.verify(exactly(1), postRequestedFor(urlPathEqualTo(ACTIVATIONS_PATH)));
        assertThat(deadLetterJobCount()).isZero();
        assertThat(queuedJobCount()).isZero();
        assertThat(timerJobCount())
                .as("the SLA and reminder timers must be cleaned up when the wait is never entered")
                .isZero();
    }

    /** Burns through every retry of the currently queued job. */
    private void exhaustRetries() {
        for (int attempt = 0; attempt < 5 && deadLetterJobCount() == 0; attempt++) {
            executeAllJobsIgnoringFailures();
            advanceClockBy(Duration.ofSeconds(11));
        }
    }

    private void executeAllJobsIgnoringFailures() {
        List<Job> jobs = managementService.createJobQuery().list();
        for (Job job : jobs) {
            try {
                managementService.executeJob(job.getId());
            } catch (RuntimeException expected) {
                // The point of the test: the failure is recorded on the job, not thrown away.
            }
        }
    }

    private int retriesLeftOnNextJob() {
        return managementService.createTimerJobQuery().list().stream()
                .map(Job::getRetries)
                .findFirst()
                .or(() -> managementService.createJobQuery().list().stream()
                        .map(Job::getRetries)
                        .findFirst())
                .orElseThrow(() -> new AssertionError("expected a job waiting to be retried"));
    }

    private void clearDeadLetterJobs() {
        managementService.createDeadLetterJobQuery().list()
                .forEach(job -> managementService.deleteDeadLetterJob(job.getId()));
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

    private String fetchOrderField(String orderId, String path) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.get(ORDERS_URL + "/" + orderId))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.parse(body).read(path, String.class);
    }
}
