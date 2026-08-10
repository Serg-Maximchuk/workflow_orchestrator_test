package com.example.sil.shared.qualification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Stored result of a qualification, so {@code GET /{id}} can answer after the fact. */
@Entity
@Table(name = "service_qualification")
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

    protected ServiceQualification() {
        // for JPA
    }

    public ServiceQualification(
            String id,
            String externalId,
            String postcode,
            String serviceSpecId,
            String qualificationResult,
            Integer maxSpeedMbps,
            String correlationId) {
        this.id = id;
        this.externalId = externalId;
        this.postcode = postcode;
        this.serviceSpecId = serviceSpecId;
        this.qualificationResult = qualificationResult;
        this.maxSpeedMbps = maxSpeedMbps;
        this.correlationId = correlationId;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getPostcode() {
        return postcode;
    }

    public String getServiceSpecId() {
        return serviceSpecId;
    }

    public String getQualificationResult() {
        return qualificationResult;
    }

    public Integer getMaxSpeedMbps() {
        return maxSpeedMbps;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
