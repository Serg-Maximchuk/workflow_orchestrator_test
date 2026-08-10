package com.example.sil.orders;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.Date;
import org.flowable.common.engine.impl.runtime.Clock;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.job.api.Job;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Base for the order workflow tests.
 *
 * <p>The async executor is switched off and jobs are executed explicitly. That is not a workaround
 * for slow tests - it is what makes them deterministic. With a background executor a test has to
 * poll and hope; driving the jobs by hand means each assertion happens at a known point in the
 * journey, and "the process has advanced exactly one step" becomes something we can state rather
 * than approximate.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:orders;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "flowable.async-executor-activate=false"
})
@AutoConfigureMockMvc
abstract class AbstractOrderWorkflowTest {

    protected static final String ORDERS_URL = "/tmf-api/serviceOrdering/v4/serviceOrder";

    protected static final String CUSTOMERS_PATH = "/supplier/v1/customers";
    protected static final String SUBSCRIPTIONS_PATH = "/supplier/v1/subscriptions";
    protected static final String USERS_PATH = "/supplier/v1/users";
    protected static final String NUMBERS_PATH = "/supplier/v1/numbers/reservations";
    protected static final String ACTIVATIONS_PATH = "/supplier/v1/numbers/activations";
    protected static final String SHIPMENTS_PATH = "/supplier/v1/shipments";

    /**
     * One stub for the whole JVM, started once and never stopped.
     *
     * <p>Not a per-class {@code @BeforeAll} server: the subclasses share a cached Spring context,
     * and its supplier base URL is resolved once from whichever port existed first. A second class
     * starting a second server on a new port would leave the application pointing at a stopped one.
     */
    protected static final WireMockServer supplier =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        supplier.start();
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ManagementService managementService;

    @Autowired
    protected RuntimeService runtimeService;

    @Autowired
    protected ProcessEngineConfigurationImpl processEngineConfiguration;

    @DynamicPropertySource
    static void supplierProperties(DynamicPropertyRegistry registry) {
        registry.add("sil.supplier.voip.base-url", () -> supplier.baseUrl());
        registry.add("spring.security.oauth2.client.provider.voip-supplier.token-uri",
                () -> supplier.baseUrl() + "/supplier/oauth/token");
    }

    protected void stubHappyPathSupplier() {
        supplier.stubFor(post(urlPathEqualTo("/supplier/oauth/token")).willReturn(okJson("""
                {"access_token":"stub-supplier-access-token",
                 "token_type":"Bearer","expires_in":3600}""")));
        supplier.stubFor(post(urlPathEqualTo(CUSTOMERS_PATH))
                .willReturn(okJson("{\"customerId\":\"cust-1\"}")));
        supplier.stubFor(post(urlPathEqualTo(SUBSCRIPTIONS_PATH))
                .willReturn(okJson("{\"subscriptionId\":\"sub-1\"}")));
        supplier.stubFor(post(urlPathEqualTo(USERS_PATH))
                .willReturn(okJson("{\"userId\":\"user-1\"}")));
        supplier.stubFor(post(urlPathEqualTo(NUMBERS_PATH))
                .willReturn(okJson("{\"phoneNumber\":\"+442071234567\",\"reservationId\":\"res-1\"}")));
        supplier.stubFor(post(urlPathEqualTo(ACTIVATIONS_PATH))
                .willReturn(okJson("{\"activationId\":\"act-1\",\"status\":\"accepted\"}")));
        supplier.stubFor(post(urlPathEqualTo(SHIPMENTS_PATH))
                .willReturn(okJson("{\"shipmentId\":\"ship-1\"}")));
        stubShipmentStatus("delivered");
        stubCompensatingCalls();
    }

    /**
     * Every provisioning call has an undo, so every test needs the undo stubbed - even the ones
     * that are not about compensation. Leaving them out makes an unrelated failure path fail for
     * the wrong reason: the unwind itself starts erroring and retrying.
     */
    protected void stubCompensatingCalls() {
        supplier.stubFor(delete(urlPathMatching("/supplier/v1/customers/.*"))
                .willReturn(aResponse().withStatus(204)));
        supplier.stubFor(delete(urlPathMatching("/supplier/v1/subscriptions/.*"))
                .willReturn(aResponse().withStatus(204)));
        supplier.stubFor(delete(urlPathMatching("/supplier/v1/users/.*"))
                .willReturn(aResponse().withStatus(204)));
        supplier.stubFor(delete(urlPathMatching("/supplier/v1/numbers/reservations/.*"))
                .willReturn(aResponse().withStatus(204)));
        supplier.stubFor(delete(urlPathMatching("/supplier/v1/numbers/activations/.*"))
                .willReturn(aResponse().withStatus(204)));
        supplier.stubFor(delete(urlPathMatching("/supplier/v1/shipments/.*"))
                .willReturn(aResponse().withStatus(204)));
    }

    protected void stubShipmentStatus(String status) {
        supplier.stubFor(get(urlPathMatching(SHIPMENTS_PATH + "/.*"))
                .willReturn(okJson("{\"shipmentId\":\"ship-1\",\"status\":\"" + status + "\"}")));
    }

    /**
     * Moves the engine clock forward and makes every timer that has now come due executable.
     *
     * <p>This is what replaces sleeping in the tests. A timer is a row with a due date, so "two
     * hours have passed" is a statement about the clock, not about wall time - which means an SLA
     * measured in hours and a poll loop measured in weeks can both be tested in milliseconds, and
     * deterministically.
     */
    protected void advanceClockBy(Duration duration) {
        Clock clock = processEngineConfiguration.getClock();
        Date now = clock.getCurrentTime();
        clock.setCurrentTime(Date.from(now.toInstant().plus(duration)));

        managementService.createTimerJobQuery().executable().list()
                .forEach(timer -> managementService.moveTimerToExecutableJob(timer.getId()));
    }

    /** Puts the clock back, so one test's time travel does not leak into the next. */
    protected void resetClock() {
        processEngineConfiguration.getClock().reset();
    }

    protected long timerJobCount() {
        return managementService.createTimerJobQuery().count();
    }

    /**
     * Takes an order that is parked on the activation callback all the way to completion: delivers
     * the callback, then moves the clock past the shipment poll timer.
     *
     * <p>Lives here because most tests care about some specific part of the journey and just need
     * the rest to happen.
     */
    protected void completeActivationAndDelivery(String orderId) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/callbacks/voip/number-activation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"%s","activated":true,"reason":""}""".formatted(orderId)))
                .andExpect(MockMvcResultMatchers.status().isAccepted());

        executeAllJobs();
        advanceClockBy(Duration.ofHours(7));
        executeAllJobs();
    }

    protected long deadLetterJobCount() {
        return managementService.createDeadLetterJobQuery().count();
    }

    /**
     * Removes process instances left behind by earlier tests.
     *
     * <p>The Spring context - and with it the H2 database and the engine - is shared by every test
     * in the class, so a test that deliberately leaves an order stuck at a failed step would
     * otherwise donate its queued job to the next test's assertions.
     */
    protected void deleteRunningProcessInstances() {
        runtimeService.createProcessInstanceQuery().list()
                .forEach(instance -> runtimeService.deleteProcessInstance(
                        instance.getId(), "cleanup between tests"));
    }

    /** Runs the next queued job, i.e. advances the process by exactly one async step. */
    protected void executeNextJob() {
        Job job = managementService.createJobQuery().listPage(0, 1).stream().findFirst()
                .orElseThrow(() -> new AssertionError("expected a job to be waiting, but none was"));
        managementService.executeJob(job.getId());
    }

    /** Drives the process to a standstill: no executable jobs left, successful or otherwise. */
    protected void executeAllJobs() {
        for (int guard = 0; guard < 50; guard++) {
            Job job = managementService.createJobQuery().listPage(0, 1).stream().findFirst().orElse(null);
            if (job == null) {
                return;
            }
            try {
                managementService.executeJob(job.getId());
            } catch (RuntimeException failed) {
                // The job stays queued with one retry fewer. Phase 3 asserts on that; here it just
                // means the journey stopped, so there is nothing more to drive.
                return;
            }
        }
        throw new AssertionError("the workflow did not settle within 50 jobs");
    }

    protected long queuedJobCount() {
        return managementService.createJobQuery().count();
    }
}
