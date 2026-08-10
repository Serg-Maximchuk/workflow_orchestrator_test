package com.example.sil.orders;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
 * What happens when the undo itself fails.
 *
 * <p>The nastiest operational state in a saga is not a failed order - it is an order stuck halfway
 * through its unwind, with some resources released at the supplier and others not. Retrying is the
 * answer, and because the whole reverse sequence runs inside one job, a retry re-runs handlers that
 * already succeeded. So the property that has to hold is not "the unwind is retried" but "the
 * unwind is safe to retry".
 */
class CompensationRetryTest extends AbstractOrderWorkflowTest {

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
        clearDeadLetterJobs();
    }

    @Test
    @DisplayName("a failing undo is retried, and the undos that already succeeded are not repeated")
    void failingUndoIsRetriedWithoutRepeatingTheOthers() throws Exception {
        // Releasing the number - the first undo in the sequence - fails once, then recovers.
        supplier.stubFor(delete(urlPathMatching("/supplier/v1/numbers/reservations/.*"))
                .inScenario("flaky release")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("recovered"));
        supplier.stubFor(delete(urlPathMatching("/supplier/v1/numbers/reservations/.*"))
                .inScenario("flaky release")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(204)));

        supplier.stubFor(post(urlPathEqualTo(ACTIVATIONS_PATH))
                .willReturn(aResponse().withStatus(422).withBody("number already ported")));

        String orderId = submitOrder();
        executeAllJobs();

        // The unwind got as far as the failing release and stopped. Nothing beyond it has run.
        assertThat(compensatingCalls()).containsExactly("/supplier/v1/numbers/reservations/+442071234567");
        assertThat(fetchOrderField(orderId, "$.supplierRefs.customerId")).isEqualTo("cust-1");

        advanceClockBy(Duration.ofSeconds(11));
        executeAllJobs();

        // The release is attempted a second time - its first attempt failed, and the bookkeeping
        // that would have marked it done was rolled back with the job anyway. This time it
        // succeeds and the rest of the sequence follows. Note what does not happen: no resource is
        // released twice for real, and the sequence still runs newest first.
        assertThat(compensatingCalls()).containsExactly(
                "/supplier/v1/numbers/reservations/+442071234567",
                "/supplier/v1/numbers/reservations/+442071234567",
                "/supplier/v1/users/user-1",
                "/supplier/v1/subscriptions/sub-1",
                "/supplier/v1/customers/cust-1");

        assertThat(fetchOrderField(orderId, "$.state")).isEqualTo("failed");
        assertThat(fetchOrderField(orderId, "$.supplierRefs.customerId")).isNull();
    }

    @Test
    @DisplayName("an undo that never recovers parks the order in the dead letter queue")
    void permanentlyFailingUndoIsDeadLettered() throws Exception {
        supplier.stubFor(delete(urlPathMatching("/supplier/v1/users/.*"))
                .willReturn(aResponse().withStatus(500)));
        supplier.stubFor(post(urlPathEqualTo(ACTIVATIONS_PATH))
                .willReturn(aResponse().withStatus(422).withBody("number already ported")));

        String orderId = submitOrder();
        exhaustRetries();

        // A half-unwound order is exactly what an operator has to be told about, rather than left
        // to discover from a reconciliation report weeks later.
        assertThat(deadLetterJobCount()).isEqualTo(1);

        String listing = mockMvc.perform(MockMvcRequestBuilders.get("/admin/workflow/dead-letter"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.parse(listing).read("$[0].orderId", String.class)).isEqualTo(orderId);

        // Worth being precise about what the order looks like now, because it is not what you
        // would guess. The number release call did reach the supplier - but the failing job rolled
        // back, and our record of having released it rolled back with it. So the row still claims
        // both the number and the user exist.
        //
        // That is the real reason a compensating call must be idempotent at the supplier: our own
        // bookkeeping of "already undone" is transactional and the remote effect is not, so the
        // retry will ask the supplier to release a number it has already released.
        assertThat(fetchOrderField(orderId, "$.supplierRefs.phoneNumber")).isEqualTo("+442071234567");
        assertThat(fetchOrderField(orderId, "$.supplierRefs.userId")).isEqualTo("user-1");

        // And once the supplier is fixed, resubmitting finishes the unwind rather than restarting it.
        supplier.stubFor(delete(urlPathMatching("/supplier/v1/users/.*"))
                .willReturn(aResponse().withStatus(204)));
        String workId = JsonPath.read(listing, "$[0].workId");
        mockMvc.perform(MockMvcRequestBuilders.post("/admin/workflow/dead-letter/" + workId + "/retry"))
                .andExpect(MockMvcResultMatchers.status().isAccepted());
        executeAllJobs();

        assertThat(fetchOrderField(orderId, "$.state")).isEqualTo("failed");
        assertThat(fetchOrderField(orderId, "$.supplierRefs.userId")).isNull();
        assertThat(fetchOrderField(orderId, "$.supplierRefs.customerId")).isNull();
    }

    /** Burns through the retry budget of whatever job is currently failing. */
    private void exhaustRetries() {
        for (int attempt = 0; attempt < 12 && deadLetterJobCount() == 0; attempt++) {
            executeAllJobsIgnoringFailures();
            advanceClockBy(Duration.ofSeconds(11));
        }
    }

    private List<String> compensatingCalls() {
        return supplier.findAll(com.github.tomakehurst.wiremock.client.WireMock
                        .anyRequestedFor(urlPathMatching("/supplier/.*")))
                .stream()
                .filter(request -> "DELETE".equals(request.getMethod().getName()))
                .sorted(Comparator.comparing(LoggedRequest::getLoggedDate))
                .map(request -> URLDecoder.decode(request.getUrl(), StandardCharsets.UTF_8))
                .toList();
    }

    private void executeAllJobsIgnoringFailures() {
        managementService.createJobQuery().list().forEach(job -> {
            try {
                managementService.executeJob(job.getId());
            } catch (RuntimeException expected) {
                // Recorded on the job, which is the point.
            }
        });
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
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String fetchOrderField(String orderId, String path) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.get(ORDERS_URL + "/" + orderId))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(body).read(path, String.class);
    }
}
