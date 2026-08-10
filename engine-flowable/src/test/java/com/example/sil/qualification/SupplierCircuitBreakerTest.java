package com.example.sil.qualification;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * The circuit breaker gets its own context because it is inherently stateful: it counts calls
 * across the whole application, so mixing it with the functional tests would make both flaky.
 *
 * <p>What it buys us: when the supplier is comprehensively down, we stop hammering it and stop
 * spending our own threads on calls that are known to fail. The caller still gets an honest 503 -
 * the difference is that it now costs nothing to produce.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:breaker;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "flowable.async-executor-activate=false",
        // One attempt per request keeps the arithmetic below obvious.
        "resilience4j.retry.instances.voipSupplier.max-attempts=1",
        "resilience4j.circuitbreaker.instances.voipSupplier.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.voipSupplier.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.voipSupplier.failure-rate-threshold=50",
        // Long enough that the breaker cannot drift to half-open mid-test.
        "resilience4j.circuitbreaker.instances.voipSupplier.wait-duration-in-open-state=60s"
})
@AutoConfigureMockMvc
class SupplierCircuitBreakerTest {

    private static final String QUALIFY_URL =
            "/tmf-api/serviceQualification/v5/checkServiceQualification";
    private static final String AVAILABILITY_PATH = "/supplier/v1/availability";

    private static WireMockServer supplier;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void startSupplierStub() {
        supplier = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        supplier.start();
        supplier.stubFor(post(urlPathEqualTo("/supplier/oauth/token"))
                .willReturn(okJson("""
                        {"access_token":"stub-supplier-access-token",
                         "token_type":"Bearer","expires_in":3600}""")));
    }

    @AfterAll
    static void stopSupplierStub() {
        supplier.stop();
    }

    @DynamicPropertySource
    static void supplierProperties(DynamicPropertyRegistry registry) {
        registry.add("sil.supplier.voip.base-url", () -> supplier.baseUrl());
        registry.add("spring.security.oauth2.client.provider.voip-supplier.token-uri",
                () -> supplier.baseUrl() + "/supplier/oauth/token");
    }

    @Test
    @DisplayName("stops calling a consistently failing supplier once the breaker opens")
    void opensAfterRepeatedFailuresAndStopsCallingTheSupplier() throws Exception {
        supplier.stubFor(post(urlPathEqualTo(AVAILABILITY_PATH))
                .willReturn(aResponse().withStatus(500)));

        // Fill the sliding window with failures: four calls, all of which reach the supplier.
        for (int i = 0; i < 4; i++) {
            qualifyExpectingServiceUnavailable();
        }
        int callsBeforeBreakerOpened = availabilityCallCount();
        assertThat(callsBeforeBreakerOpened)
                .as("every call before the window filled should have reached the supplier")
                .isEqualTo(4);

        // The breaker is open now: further requests fail without touching the network at all.
        qualifyExpectingServiceUnavailable();
        qualifyExpectingServiceUnavailable();

        assertThat(availabilityCallCount())
                .as("an open breaker must short-circuit instead of calling the supplier")
                .isEqualTo(callsBeforeBreakerOpened);
    }

    private void qualifyExpectingServiceUnavailable() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post(QUALIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalId":"OMS-4711",
                                 "place":{"postcode":"SW1A 1AA"},
                                 "serviceSpecId":"VOIP_BUSINESS",
                                 "requestedSpeedMbps":100}"""))
                .andExpect(MockMvcResultMatchers.status().isServiceUnavailable());
    }

    private int availabilityCallCount() {
        return supplier.countRequestsMatching(
                postRequestedFor(urlPathEqualTo(AVAILABILITY_PATH)).build())
                .getCount();
    }
}
