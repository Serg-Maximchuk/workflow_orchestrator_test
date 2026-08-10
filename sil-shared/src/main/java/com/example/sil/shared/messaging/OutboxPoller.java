package com.example.sil.shared.messaging;

import java.util.List;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns outbox rows into messages.
 *
 * <p>Publishing and marking the row cannot be made atomic either, and the direction of the
 * remaining risk is chosen deliberately: publish first, mark second. A crash in between resends the
 * event, which the consumer's guard absorbs. The other order would lose events outright, and a lost
 * "your order completed" is not recoverable by anybody.
 */
@Component
@ConditionalOnProperty(name = "sil.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxEventRepository outbox;
    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;

    public OutboxPoller(OutboxEventRepository outbox, RabbitTemplate rabbitTemplate,
                        MessagingProperties properties) {
        this.outbox = outbox;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${sil.messaging.poll-interval:1s}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending =
                outbox.findByPublishedAtIsNullOrderByCreatedAtAsc(Limit.of(properties.batchSize()));

        for (OutboxEvent event : pending) {
            try {
                rabbitTemplate.send(properties.exchange(), properties.routingKey(),
                        MessageBuilder.withBody(event.getPayload().getBytes())
                                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                                // The outbox row id becomes the message id, which is what lets the
                                // consumer recognise a redelivery of the same event.
                                .setMessageId(event.getId())
                                .setHeader("correlationId", event.getCorrelationId())
                                .setHeader("eventType", event.getEventType())
                                .build());
                event.published();
            } catch (RuntimeException e) {
                // Left unpublished for the next sweep. Nothing is lost; the event simply arrives late.
                event.attemptFailed();
                log.warn("Could not publish outbox event {} (attempt {}): {}",
                        event.getId(), event.getAttempts(), e.toString());
            }
        }
    }
}
