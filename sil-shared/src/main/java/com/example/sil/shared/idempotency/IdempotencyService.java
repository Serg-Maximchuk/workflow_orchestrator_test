package com.example.sil.shared.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * HTTP-level idempotency: a retried {@code POST} carrying the same {@code Idempotency-Key} must
 * produce one resource, not two.
 *
 * <p>The guarantee comes from the primary key on {@code idempotency_record}, not from a
 * check-then-act in Java: two concurrent duplicates both try to insert, exactly one wins, and the
 * loser reads the winner's result. Reusing a key with a different body is rejected rather than
 * silently returning the wrong resource.
 *
 * <p>This is the first of the three idempotency layers in the project. The other two arrive later:
 * the engine's job executor (a job that runs twice must not call the supplier twice) and the queue
 * consumer (at-least-once delivery must have an at-most-once effect).
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository repository;
    private final IdempotencyRecordWriter writer;

    public IdempotencyService(IdempotencyRecordRepository repository, IdempotencyRecordWriter writer) {
        this.repository = repository;
        this.writer = writer;
    }

    /**
     * Runs {@code action} at most once per idempotency key.
     *
     * @param idempotencyKey key supplied by the caller; when blank the action always runs
     * @param requestBody canonical request body, fingerprinted to detect key reuse
     * @param action creates the resource and returns its id
     * @return the resource id, either freshly created or replayed from a previous call
     */
    public IdempotentOutcome execute(String idempotencyKey, String requestBody, Supplier<String> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return new IdempotentOutcome(action.get(), false);
        }

        String fingerprint = fingerprint(requestBody);

        Optional<IdempotencyRecord> existing = repository.findById(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), idempotencyKey, fingerprint);
        }

        String resourceId = action.get();
        try {
            writer.save(new IdempotencyRecord(idempotencyKey, fingerprint, resourceId, 201));
        } catch (DataIntegrityViolationException raceLost) {
            // A concurrent duplicate inserted first. Its result is the one the caller gets;
            // whatever this thread created is discarded by the caller's transaction rollback.
            log.info("Lost the idempotency insert race for key {}, replaying the winner", idempotencyKey);
            IdempotencyRecord winner = repository.findById(idempotencyKey)
                    .orElseThrow(() -> raceLost);
            return replay(winner, idempotencyKey, fingerprint);
        }
        return new IdempotentOutcome(resourceId, false);
    }

    private IdempotentOutcome replay(IdempotencyRecord record, String key, String fingerprint) {
        if (!record.getRequestFingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReuseException(key);
        }
        log.info("Replaying idempotent response for key {} -> {}", key, record.getResourceId());
        return new IdempotentOutcome(record.getResourceId(), true);
    }

    private String fingerprint(String requestBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(requestBody.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }

    /** @param replayed true when the resource came from a previous call rather than this one */
    public record IdempotentOutcome(String resourceId, boolean replayed) {}
}
