package com.example.sil.shared.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * One row per message the consumer has already acted on.
 *
 * <p>The counterpart to the outbox's at-least-once delivery. The primary key does the work: a
 * redelivered message loses the insert and is dropped, so "the broker delivered it twice" turns
 * into "it happened once".
 *
 * <p>{@link Persistable} is what makes that true rather than merely intended. Spring Data decides
 * whether to persist or merge by looking at the identifier, and an assigned one makes it choose
 * merge - which quietly updates the existing row instead of violating the primary key, so the
 * duplicate is never detected and the listener is notified twice. Saying "always new" forces the
 * insert, and the insert is the check.
 */
@Entity
@Table(name = "processed_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA
public class ProcessedMessage implements Persistable<String> {

    @Id
    @Column(name = "message_id", nullable = false, length = 50)
    private String messageId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public ProcessedMessage(String messageId) {
        this.messageId = messageId;
        this.processedAt = Instant.now();
    }

    @Override
    public String getId() {
        return messageId;
    }

    @Override
    public boolean isNew() {
        // This row is only ever inserted; an existing one means the message is a duplicate.
        return true;
    }
}
