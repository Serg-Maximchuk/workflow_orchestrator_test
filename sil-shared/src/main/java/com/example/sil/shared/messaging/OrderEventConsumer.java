package com.example.sil.shared.messaging;

import com.example.sil.shared.correlation.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * Delivers an order event onwards to the client's listener.
 *
 * <p>The third and last place idempotency shows up in this project, after the HTTP API and the job
 * executor. Here it is a row in {@code processed_message}: the insert and the delivery share one
 * transaction, so a message that arrives twice is recognised and dropped rather than notifying the
 * client twice about the same order.
 */
@Component
@ConditionalOnProperty(name = "sil.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final ProcessedMessageRepository processedMessages;
    private final RestClient listenerClient;
    private final MessagingProperties properties;

    public OrderEventConsumer(ProcessedMessageRepository processedMessages,
                              MessagingProperties properties) {
        this.processedMessages = processedMessages;
        this.properties = properties;

        // Built here rather than injected: the auto-configured RestClient.Builder is not available
        // once the application defines its own RestClient bean, and a listener that hangs must not
        // be able to block a consumer thread indefinitely.
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(5));
        this.listenerClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    // A property placeholder rather than a SpEL bean reference: a @ConfigurationProperties record
    // is registered under a generated bean name, so "@messagingProperties" resolves to nothing.
    @RabbitListener(queues = "${sil.messaging.queue:sil.order.events.queue}")
    @Transactional
    public void onOrderEvent(Message message) {
        String messageId = message.getMessageProperties().getMessageId();
        // getHeader is generic and infers its type from the target. Passing it straight to
        // String.valueOf picks the char[] overload and fails at runtime with a ClassCastException,
        // so the type is pinned here.
        Object rawCorrelationId = message.getMessageProperties().getHeader("correlationId");
        String correlationId = String.valueOf(rawCorrelationId);

        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        try {
            processedMessages.saveAndFlush(new ProcessedMessage(messageId));
        } catch (DataIntegrityViolationException alreadyHandled) {
            log.info("Dropping redelivered message {}", messageId);
            return;
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }

        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        try {
            listenerClient.post()
                    .uri(properties.listenerUrl())
                    .header("Content-Type", "application/json")
                    .header(CorrelationIdFilter.HEADER, correlationId)
                    .body(new String(message.getBody()))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Delivered {} to the listener", messageId);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
