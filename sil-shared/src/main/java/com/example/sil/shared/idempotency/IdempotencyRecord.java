package com.example.sil.shared.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One row per {@code Idempotency-Key} the API has seen, holding the response that was returned the
 * first time. The primary key is the idempotency key itself, so a concurrent duplicate loses the
 * insert race at the database level rather than in application logic.
 *
 * <p>Only {@code @Getter} and a protected no-args constructor are generated. Deliberately not
 * {@code @Data} or {@code @EqualsAndHashCode}: an entity's identity is its primary key, and
 * generating equality from all fields breaks as soon as a mutable field changes while the instance
 * sits in a collection. Setters are left out for the same reason the fields are only assigned in
 * the constructor - this row is written once and never updated.
 */
@Entity
@Table(name = "idempotency_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    /** Fingerprint of the original request body: the same key with a different body is a caller bug. */
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "resource_id", nullable = false, length = 100)
    private String resourceId;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public IdempotencyRecord(
            String idempotencyKey, String requestFingerprint, String resourceId, int responseStatus) {
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.resourceId = resourceId;
        this.responseStatus = responseStatus;
        this.createdAt = Instant.now();
    }
}
