package com.example.sil.shared.idempotency;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the idempotency row in its own transaction.
 *
 * <p>Deliberately a separate bean rather than a method on {@link IdempotencyService}:
 * {@code @Transactional} is applied by a proxy, so a self-invocation inside the service would run
 * with no new transaction at all. The separate transaction also matters on failure - when the
 * insert loses the race, only this inner transaction is marked rollback-only, leaving the caller's
 * transaction able to read and return the winner's response.
 */
@Component
public class IdempotencyRecordWriter {

    private final IdempotencyRecordRepository repository;

    public IdempotencyRecordWriter(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(IdempotencyRecord record) {
        repository.saveAndFlush(record);
    }
}
