package com.example.sil.recovery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.example.sil.ServiceIntegrationLayerApplication;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for the tests that prove the workflow survives the application it runs in.
 *
 * <p>These are the only tests in the project that boot the application by hand instead of letting
 * Spring's test framework cache a context, and it has to be that way: the thing under test is the
 * lifecycle itself. A cached context cannot be killed and brought back, and killing it is the
 * entire experiment.
 *
 * <p>The async executor is left on and time is real here, for the same reason - a restart proves
 * nothing if a test is driving the jobs by hand.
 */
@Tag("integration")
abstract class AbstractRestartTest {

    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    protected static final WireMockServer SUPPLIER =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        POSTGRES.start();
        SUPPLIER.start();
    }

    protected static final String CUSTOMERS_PATH = "/supplier/v1/customers";
    protected static final String SUBSCRIPTIONS_PATH = "/supplier/v1/subscriptions";
    protected static final String USERS_PATH = "/supplier/v1/users";
    protected static final String NUMBERS_PATH = "/supplier/v1/numbers/reservations";
    protected static final String ACTIVATIONS_PATH = "/supplier/v1/numbers/activations";
    protected static final String SHIPMENTS_PATH = "/supplier/v1/shipments";

    private final HttpClient http = HttpClient.newHttpClient();

    /** Boots a fresh instance of the application against the shared database. */
    protected ConfigurableApplicationContext startInstance() {
        // Passed as command-line arguments rather than through properties(): the builder's
        // properties become default properties, which application.yml then overrides.
        return new SpringApplicationBuilder(ServiceIntegrationLayerApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--sil.supplier.voip.base-url=" + SUPPLIER.baseUrl(),
                "--spring.security.oauth2.client.provider.voip-supplier.token-uri="
                        + SUPPLIER.baseUrl() + "/supplier/oauth/token",
                // Short enough that the shipment poll actually happens during a test, long enough
                // that it does not fire before the restart under test.
                "--sil.workflow.shipment-poll-delay=PT3S");
    }

    protected int portOf(ConfigurableApplicationContext context) {
        return Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    }

    protected void stubSupplier() {
        SUPPLIER.stubFor(post(urlPathEqualTo("/supplier/oauth/token")).willReturn(okJson("""
                {"access_token":"stub-token","token_type":"Bearer","expires_in":3600}""")));
        SUPPLIER.stubFor(post(urlPathEqualTo(CUSTOMERS_PATH))
                .willReturn(okJson("{\"customerId\":\"cust-1\"}")));
        SUPPLIER.stubFor(post(urlPathEqualTo(SUBSCRIPTIONS_PATH))
                .willReturn(okJson("{\"subscriptionId\":\"sub-1\"}")));
        SUPPLIER.stubFor(post(urlPathEqualTo(USERS_PATH))
                .willReturn(okJson("{\"userId\":\"user-1\"}")));
        SUPPLIER.stubFor(post(urlPathEqualTo(NUMBERS_PATH))
                .willReturn(okJson("{\"phoneNumber\":\"+442071234567\",\"reservationId\":\"res-1\"}")));
        SUPPLIER.stubFor(post(urlPathEqualTo(ACTIVATIONS_PATH))
                .willReturn(okJson("{\"activationId\":\"act-1\",\"status\":\"accepted\"}")));
        SUPPLIER.stubFor(post(urlPathEqualTo(SHIPMENTS_PATH))
                .willReturn(okJson("{\"shipmentId\":\"ship-1\"}")));
        SUPPLIER.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .get(urlPathMatching(SHIPMENTS_PATH + "/.*"))
                .willReturn(okJson("{\"shipmentId\":\"ship-1\",\"status\":\"delivered\"}")));
        SUPPLIER.stubFor(delete(urlPathMatching("/supplier/.*"))
                .willReturn(aResponse().withStatus(204)));
    }

    protected String submitOrder(int port) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port
                        + "/tmf-api/serviceOrdering/v4/serviceOrder"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"externalId":"OMS-%s",
                         "customer":{"name":"Acme Ltd","email":"ops@acme.example"},
                         "place":{"postcode":"SW1A 1AA"},
                         "serviceSpecId":"VOIP_BUSINESS",
                         "speedMbps":100}""".formatted(UUID.randomUUID())))
                .build());
        return JsonPath.read(response.body(), "$.id");
    }

    protected String orderJson(int port, String orderId) throws Exception {
        return send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port
                        + "/tmf-api/serviceOrdering/v4/serviceOrder/" + orderId))
                .GET()
                .build()).body();
    }

    /**
     * Delivers the activation callback, retrying until the process is actually parked on the
     * message. A callback that arrives while the activation request is still in flight is refused
     * with a 409 by design - which is correct behaviour and a race the supplier would hit too.
     */
    protected void deliverActivationCallback(int port, String orderId) throws Exception {
        awaitValue(() -> send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/callbacks/voip/number-activation"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"orderId":"%s","activated":true,"reason":""}""".formatted(orderId)))
                        .build()).statusCode(),
                status -> status == 202);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Polls until the condition holds. Real time is unavoidable here - the async executor is doing
     * the work - so the tests wait for an observable outcome rather than for a fixed duration.
     */
    protected <T> T awaitValue(Callable<T> probe, java.util.function.Predicate<T> until)
            throws Exception {
        Duration timeout = Duration.ofSeconds(30);
        long deadline = System.nanoTime() + timeout.toNanos();
        T value = null;
        while (System.nanoTime() < deadline) {
            value = probe.call();
            if (until.test(value)) {
                return value;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("condition never held within " + timeout + ", last value: " + value);
    }
}
