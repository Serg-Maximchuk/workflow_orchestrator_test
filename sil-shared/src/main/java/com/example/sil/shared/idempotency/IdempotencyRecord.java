package com.example.sil.shared.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One row per {@code Idempotency-Key} the API has seen, holding the response that was returned the
 * first time. The primary key is the idempotency key itself, so a concurrent duplicate loses the
 * insert race at the database level rather than in application logic.
 */
@Entity
@Table(name = "idempotency_record")
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

    protected IdempotencyRecord() {
        // for JPA
    }

    public IdempotencyRecord(
            String idempotencyKey, String requestFingerprint, String resourceId, int responseStatus) {
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.resourceId = resourceId;
        this.responseStatus = responseStatus;
        this.createdAt = Instant.now();
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getResourceId() {
        return resourceId;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
