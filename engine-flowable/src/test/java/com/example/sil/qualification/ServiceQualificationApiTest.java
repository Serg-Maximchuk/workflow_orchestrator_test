package com.example.sil.qualification;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Phase 1 acceptance tests for TMF645 Service Qualification and the cross-cutting concerns it
 * establishes: idempotency, correlation ids, timeouts, retry and the OAuth2 token on outbound calls.
 *
 * <p>Runs in the fast lane: H2 plus an in-process WireMock, so no Docker is involved. The supplier
 * is only ever a stub here, which is the point - development must not depend on a third-party
 * sandbox being up.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:qualification;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "sil.messaging.enabled=false",
        "flowable.async-executor-activate=false",
        // Keep the retry test quick; the production values back off much further.
        "resilience4j.retry.instances.voipSupplier.wait-duration=10ms",
        "sil.supplier.voip.read-timeout=1s",
        // The circuit breaker must not trip during these tests, or the deliberate failures below
        // would be masked by an open breaker instead of surfacing as the behaviour under test.
        // Both values have to be raised: for a count-based window resilience4j caps
        // minimumNumberOfCalls at slidingWindowSize, so raising only one of them does nothing.
        "resilience4j.circuitbreaker.instances.voipSupplier.sliding-window-size=1000",
        "resilience4j.circuitbreaker.instances.voipSupplier.minimum-number-of-calls=1000"
})
@AutoConfigureMockMvc
class ServiceQualificationApiTest {

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

    @BeforeEach
    void resetStub() {
        supplier.resetAll();
        supplier.stubFor(post(urlPathEqualTo("/supplier/oauth/token"))
                .willReturn(okJson("""
                        {"access_token":"stub-supplier-access-token",
                         "token_type":"Bearer","expires_in":3600}""")));
    }

    @Test
    @DisplayName("qualifies a serviceable address and stores the answer for later retrieval")
    void qualifiesAndStoresResult() throws Exception {
        stubAvailability(true, 900);

        MvcResult created = mockMvc.perform(MockMvcRequestBuilders.post(QUALIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("SW1A 1AA")))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andExpect(MockMvcResultMatchers.jsonPath("$.qualificationResult").value("qualified"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.maxSpeedMbps").value(900))
                .andReturn();

        String id = jsonField(created, "id");

        mockMvc.perform(MockMvcRequestBuilders.get(QUALIFY_URL + "/" + id))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.id").value(id))
                .andExpect(MockMvcResultMatchers.jsonPath("$.qualificationResult").value("qualified"));
    }

    @Test
    @DisplayName("reports an address outside the supplier footprint as unqualified")
    void reportsUnqualifiedAddress() throws Exception {
        stubAvailability(false, 0);

        mockMvc.perform(MockMvcRequestBuilders.post(QUALIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("ZZ99 9ZZ")))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andExpect(MockMvcResultMatchers.jsonPath("$.qualificationResult").value("unqualified"));
    }

    @Test
    @DisplayName("an unknown qualification id is a 404 problem response")
    void unknownIdIsNotFound() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(QUALIFY_URL + "/sq-does-not-exist"))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(MockMvcResultMatchers.jsonPath("$.title").value("Qualification not found"));
    }

    @Test
    @DisplayName("the same Idempotency-Key returns the first result and calls the supplier once")
    void replaysIdempotentRequest() throws Exception {
        stubAvailability(true, 900);
        String key = UUID.randomUUID().toString();

        MvcResult first = performWithKey(key, requestBody("SW1A 1AA"), HttpStatus.CREATED);
        MvcResult second = performWithKey(key, requestBody("SW1A 1AA"), HttpStatus.CREATED);

        assertThat(jsonField(second, "id"))
                .as("a retried request must not create a second qualification")
                .isEqualTo(jsonField(first, "id"));

        supplier.verify(exactly(1), postRequestedFor(urlPathEqualTo(AVAILABILITY_PATH)));
    }

    @Test
    @DisplayName("reusing an Idempotency-Key with a different body is a 409, not a wrong replay")
    void rejectsIdempotencyKeyReuse() throws Exception {
        stubAvailability(true, 900);
        String key = UUID.randomUUID().toString();

        performWithKey(key, requestBody("SW1A 1AA"), HttpStatus.CREATED);

        mockMvc.perform(MockMvcRequestBuilders.post(QUALIFY_URL)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("EC1A 1BB")))
                .andExpect(MockMvcResultMatchers.status().isConflict())
                .andExpect(MockMvcResultMatchers.jsonPath("$.title").value("Idempotency-Key reused"));
    }

    @Test
    @DisplayName("retries a failing supplier and succeeds on a later attempt")
    void retriesTransientSupplierFailure() throws Exception {
        supplier.stubFor(post(urlPathEqualTo(AVAILABILITY_PATH))
                .inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("failed once"));
        supplier.stubFor(post(urlPathEqualTo(AVAILABILITY_PATH))
                .inScenario("flaky")
                .whenScenarioStateIs("failed once")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("failed twice"));
        supplier.stubFor(post(urlPathEqualTo(AVAILABILITY_PATH))
                .inScenario("flaky")
                .whenScenarioStateIs("failed twice")
                .willReturn(okJson(availabilityBody(true, 500))));

        mockMvc.perform(MockMvcRequestBuilders.post(QUALIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("SW1A 1AA")))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andExpect(MockMvcResultMatchers.jsonPath("$.qualificationResult").value("qualified"));

        supplier.verify(exactly(3), postRequestedFor(urlPathEqualTo(AVAILABILITY_PATH)));
    }

    @Test
    @DisplayName("a supplier that never recovers surfaces as 503 after the attempts are exhausted")
    void exhaustedRetriesBecomeServiceUnavailable() throws Exception {
        supplier.stubFor(post(urlPathEqualTo(AVAILABILITY_PATH))
                .willReturn(aResponse().withStatus(500)));

        mockMvc.perform(MockMvcRequestBuilders.post(QUALIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("SW1A 1AA")))
                .andExpect(MockMvcResultMatchers.status().isServiceUnavailable());

        // max-attempts = 4 means the original call plus three retries, and then it gives up.
        supplier.verify(exactly(4), postRequestedFor(urlPathEqualTo(AVAILABILITY_PATH)));
    }

    @Test
    @DisplayName("a supplier slower than the read timeout fails fast instead of hanging")
    void slowSupplierHitsTheReadTimeout() throws Exception {
        supplier.stubFor(post(urlPathEqualTo(AVAILABILITY_PATH))
                .willReturn(okJson(availabilityBody(true, 900)).withFixedDelay(10_000)));

        long startedAt = System.currentTimeMillis();
        mockMvc.perform(MockMvcRequestBuilders.post(QUALIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("SW1A 1AA")))
                .andExpect(MockMvcResultMatchers.status().isServiceUnavailable());
        long elapsed = System.currentTimeMillis() - startedAt;

        // The supplier takes 10s to answer and four attempts are made, so without a read timeout
        // this call would take at least 40s. Bounded by the 1s timeout it takes about four.
        assertThat(elapsed)
                .as("the call must be bounded by our timeout, not by the supplier's latency")
                .isLessThan(8_000);
        supplier.verify(exactly(4), postRequestedFor(urlPathEqualTo(AVAILABILITY_PATH)));
    }

    @Test
    @DisplayName("the caller's correlation id is echoed back and forwarded to the supplier")
    void propagatesCorrelationId() throws Exception {
        stubAvailability(true, 900);
        String correlationId = "oms-" + UUID.randomUUID();

        mockMvc.perform(MockMvcRequestBuilders.post(QUALIFY_URL)
                        .header("X-Correlation-Id", correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("SW1A 1AA")))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andExpect(MockMvcResultMatchers.header().string("X-Correlation-Id", correlationId))
                .andExpect(MockMvcResultMatchers.jsonPath("$.correlationId").value(correlationId));

        supplier.verify(postRequestedFor(urlPathEqualTo(AVAILABILITY_PATH))
                .withHeader("X-Correlation-Id", equalTo(correlationId)));
    }

    @Test
    @DisplayName("outbound supplier calls carry an OAuth2 client-credentials bearer token")
    void sendsOAuth2BearerToken() throws Exception {
        stubAvailability(true, 900);

        mockMvc.perform(MockMvcRequestBuilders.post(QUALIFY_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("SW1A 1AA")))
                .andExpect(MockMvcResultMatchers.status().isCreated());

        supplier.verify(postRequestedFor(urlPathEqualTo(AVAILABILITY_PATH))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer stub-supplier-access-token")));
    }

    private MvcResult performWithKey(String key, String body, HttpStatus expected) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(QUALIFY_URL)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(MockMvcResultMatchers.status().is(expected.value()))
                .andReturn();
    }

    private void stubAvailability(boolean available, int maxSpeed) {
        supplier.stubFor(post(urlPathEqualTo(AVAILABILITY_PATH))
                .willReturn(okJson(availabilityBody(available, maxSpeed))));
    }

    private static String availabilityBody(boolean available, int maxSpeed) {
        return """
                {"available":%s,"offeredServiceSpecIds":["VOIP_BUSINESS"],"maxSpeedMbps":%d}"""
                .formatted(available, maxSpeed);
    }

    private static String requestBody(String postcode) {
        return """
                {"externalId":"OMS-4711",
                 "place":{"postcode":"%s","streetAddress":"10 Downing Street"},
                 "serviceSpecId":"VOIP_BUSINESS",
                 "requestedSpeedMbps":100}""".formatted(postcode);
    }

    private static String jsonField(MvcResult result, String field) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$." + field);
    }
}
