package com.example.sil.shared.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An event that must be published, written in the same transaction as the change it describes.
 *
 * <p>The problem it solves: a broker and a database cannot be updated atomically. Publish before
 * committing and a rollback leaves the world told about something that never happened; publish
 * after committing and a crash in between leaves it never told. Writing the intent to this table
 * inside the business transaction removes the window - either both the order change and the row
 * exist, or neither does - and a poller turns the row into an actual message afterwards.
 *
 * <p>The trade is at-least-once delivery: the poller can publish and then fail before marking the
 * row, so the same event goes out twice. That is why the consumer has its own guard.
 */
@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, length = 50)
    private String id;

    /** The order this event is about; also the message's partition key if one is ever needed. */
    @Column(name = "aggregate_id", nullable = false, length = 50)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "correlation_id", nullable = false, length = 60)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Null until the poller has handed it to the broker. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    public OutboxEvent(String aggregateId, String eventType, String payload, String correlationId) {
        this.id = UUID.randomUUID().toString();
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.correlationId = correlationId;
        this.createdAt = Instant.now();
    }

    public void published() {
        this.publishedAt = Instant.now();
    }

    public void attemptFailed() {
        this.attempts++;
    }
}
