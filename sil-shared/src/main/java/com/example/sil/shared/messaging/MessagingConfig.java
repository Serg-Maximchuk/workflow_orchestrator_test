package com.example.sil.shared.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Broker topology for order events.
 *
 * <p>Conditional on {@code sil.messaging.enabled} so the fast test lane, which has no broker, does
 * not spend its life retrying a connection to one.
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
@EnableScheduling
@ConditionalOnProperty(name = "sil.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class MessagingConfig {

    @Bean
    TopicExchange orderEventsExchange(MessagingProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }

    @Bean
    Queue orderEventsQueue(MessagingProperties properties) {
        return new Queue(properties.queue(), true);
    }

    @Bean
    Binding orderEventsBinding(TopicExchange orderEventsExchange, Queue orderEventsQueue,
                               MessagingProperties properties) {
        return BindingBuilder.bind(orderEventsQueue).to(orderEventsExchange).with(properties.routingKey());
    }
}
