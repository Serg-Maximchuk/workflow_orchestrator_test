package com.example.sil.recovery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.sil.shared.messaging.MessagingProperties;
import com.example.sil.shared.messaging.OutboxEventRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * The outbox: an order change and the news of it cannot get out of step.
 *
 * <p>Two properties, and they are opposites of each other. Nothing is lost, because the event is
 * written in the same transaction as the order change rather than sent from inside it. And nothing
 * happens twice, because the consumer recognises a redelivery - which the broker is entitled to
 * make, and which the poller itself can cause by publishing and then failing before it marks the
 * row as sent.
 */
class OutboxDeliveryIT extends AbstractRestartTest {

    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-alpine");

    private static final String LISTENER_PATH = "/listener/order-events";

    static {
        RABBIT.start();
    }

    @BeforeEach
    void resetSupplier() {
        SUPPLIER.resetAll();
        stubSupplier();
        SUPPLIER.stubFor(post(urlPathEqualTo(LISTENER_PATH)).willReturn(aResponse().withStatus(204)));
    }

    @Override
    protected ConfigurableApplicationContext startInstance() {
        return new org.springframework.boot.builder.SpringApplicationBuilder(
                com.example.sil.ServiceIntegrationLayerApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--sil.supplier.voip.base-url=" + SUPPLIER.baseUrl(),
                "--spring.security.oauth2.client.provider.voip-supplier.token-uri="
                        + SUPPLIER.baseUrl() + "/supplier/oauth/token",
                "--sil.workflow.shipment-poll-delay=PT3S",
                "--spring.rabbitmq.host=" + RABBIT.getHost(),
                "--spring.rabbitmq.port=" + RABBIT.getAmqpPort(),
                "--spring.rabbitmq.username=" + RABBIT.getAdminUsername(),
                "--spring.rabbitmq.password=" + RABBIT.getAdminPassword(),
                "--sil.messaging.listener-url=" + SUPPLIER.baseUrl() + LISTENER_PATH);
    }

    @Test
    @DisplayName("a completed order is announced to the listener exactly once")
    void completedOrderIsDeliveredOnce() throws Exception {
        ConfigurableApplicationContext app = startInstance();
        try {
            int port = portOf(app);
            String orderId = submitOrder(port);

            awaitValue(() -> orderJson(port, orderId),
                    json -> JsonPath.<String>read(json, "$.supplierRefs.phoneNumber") != null);
            deliverActivationCallback(port, orderId);
            awaitValue(() -> orderJson(port, orderId),
                    json -> "completed".equals(JsonPath.<String>read(json, "$.state")));

            // The event was written with the order change; the poller turns it into a message and
            // the consumer forwards it. All of that is asynchronous, so wait for the outcome.
            awaitValue(() -> deliveriesFor(orderId).size(), count -> count == 1);

            String delivered = deliveriesFor(orderId).getFirst();
            assertThat(JsonPath.<String>read(delivered, "$.eventType")).isEqualTo("ServiceOrderCompleted");
            assertThat(JsonPath.<String>read(delivered, "$.orderId")).isEqualTo(orderId);
            assertThat(JsonPath.<String>read(delivered, "$.phoneNumber")).isEqualTo("+442071234567");

            assertThat(app.getBean(OutboxEventRepository.class).countByPublishedAtIsNull())
                    .as("the outbox drains rather than growing")
                    .isZero();
        } finally {
            app.close();
        }
    }

    /**
     * Deliveries about one order. Scoped deliberately: every test in this package shares one
     * database, so the poller of a later instance quite correctly drains events left by earlier
     * ones - a global count would measure the suite rather than the behaviour.
     */
    private java.util.List<String> deliveriesFor(String orderId) {
        return SUPPLIER.findAll(postRequestedFor(urlPathEqualTo(LISTENER_PATH))).stream()
                .map(request -> request.getBodyAsString())
                .filter(body -> body.contains(orderId))
                .toList();
    }

    @Test
    @DisplayName("a redelivered message does not notify the listener twice")
    void redeliveryIsAbsorbedByTheConsumer() throws Exception {
        ConfigurableApplicationContext app = startInstance();
        try {
            int port = portOf(app);
            String orderId = submitOrder(port);

            awaitValue(() -> orderJson(port, orderId),
                    json -> JsonPath.<String>read(json, "$.supplierRefs.phoneNumber") != null);
            deliverActivationCallback(port, orderId);
            awaitValue(() -> orderJson(port, orderId),
                    json -> "completed".equals(JsonPath.<String>read(json, "$.state")));
            awaitValue(() -> deliveriesFor(orderId).size(), count -> count == 1);

            // Republish the very same message, which is exactly what the broker does after a
            // consumer dies between acting and acknowledging - and what the poller does if it
            // crashes after publishing but before marking the row.
            String payload = deliveriesFor(orderId).getFirst();
            String originalMessageId = app.getBean(OutboxEventRepository.class).findAll().stream()
                    .filter(event -> event.getAggregateId().equals(orderId))
                    .findFirst()
                    .orElseThrow()
                    .getId();

            MessagingProperties properties = app.getBean(MessagingProperties.class);
            app.getBean(RabbitTemplate.class).send(properties.exchange(), properties.routingKey(),
                    MessageBuilder.withBody(payload.getBytes())
                            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                            .setMessageId(originalMessageId)
                            .setHeader("correlationId", "redelivery-probe")
                            .setHeader("eventType", "ServiceOrderCompleted")
                            .build());

            // Give the consumer time to receive and drop it, then confirm nothing further arrived.
            Thread.sleep(2000);
            assertThat(deliveriesFor(orderId))
                    .as("a redelivered message must not notify the listener again")
                    .hasSize(1);
        } finally {
            app.close();
        }
    }
}
