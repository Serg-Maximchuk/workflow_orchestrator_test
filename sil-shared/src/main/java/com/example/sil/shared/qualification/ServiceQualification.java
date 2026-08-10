package com.example.sil.shared.qualification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Stored result of a qualification, so {@code GET /{id}} can answer after the fact.
 *
 * <p>Built through {@code @Builder} because seven constructor arguments of which five are strings
 * is a swap waiting to happen - {@code postcode} and {@code serviceSpecId} in the wrong order would
 * compile perfectly. See {@link com.example.sil.shared.idempotency.IdempotencyRecord} for why this
 * carries {@code @Getter} but not {@code @Data}.
 */
@Entity
@Table(name = "service_qualification")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA
@AllArgsConstructor(access = AccessLevel.PRIVATE) // used by the builder
public class ServiceQualification {

    @Id
    @Column(name = "id", nullable = false, length = 50)
    private String id;

    @Column(name = "external_id", length = 100)
    private String externalId;

    @Column(name = "postcode", nullable = false, length = 20)
    private String postcode;

    @Column(name = "service_spec_id", nullable = false, length = 60)
    private String serviceSpecId;

    @Column(name = "qualification_result", nullable = false, length = 30)
    private String qualificationResult;

    @Column(name = "max_speed_mbps")
    private Integer maxSpeedMbps;

    @Column(name = "correlation_id", nullable = false, length = 60)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
