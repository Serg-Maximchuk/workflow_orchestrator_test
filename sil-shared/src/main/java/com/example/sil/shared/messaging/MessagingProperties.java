package com.example.sil.shared.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled turn the outbox poller and the consumer off where there is no broker, such as the
 *     fast test lane
 * @param exchange AMQP exchange order events are published to
 * @param routingKey routing key used for every order event
 * @param queue queue the consumer reads
 * @param pollInterval how often the outbox is swept for unpublished events
 * @param batchSize how many events one sweep publishes
 * @param listenerUrl where a delivered event is forwarded, standing in for the client's listener
 */
@ConfigurationProperties(prefix = "sil.messaging")
public record MessagingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("sil.order.events") String exchange,
        @DefaultValue("order.event") String routingKey,
        @DefaultValue("sil.order.events.queue") String queue,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("http://localhost:8081/listener/order-events") String listenerUrl) {}
