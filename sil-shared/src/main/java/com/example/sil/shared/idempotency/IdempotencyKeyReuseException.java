package com.example.sil.shared.idempotency;

/**
 * Thrown when a caller reuses an {@code Idempotency-Key} with a different request body. Replaying
 * the stored response would answer a question the caller did not ask, so this is surfaced as a
 * client error instead.
 */
public class IdempotencyKeyReuseException extends RuntimeException {

    public IdempotencyKeyReuseException(String key) {
        super("Idempotency-Key '" + key + "' was already used with a different request body");
    }
}
