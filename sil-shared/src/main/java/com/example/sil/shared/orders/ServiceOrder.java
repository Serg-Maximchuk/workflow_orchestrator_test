package com.example.sil.shared.orders;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The order as <em>we</em> know it, separate from the workflow instance that fulfils it.
 *
 * <p>Keeping our own row rather than reading everything back out of the engine matters for two
 * reasons. The API has to answer {@code GET /serviceOrder/{id}} long after the process has finished
 * and its runtime state has been deleted; and business queries ("all failed orders for this
 * customer") belong in SQL against our own schema, not in engine queries over process variables.
 *
 * <p>The engine still owns <em>where the journey is</em>. This row records what the journey has
 * achieved so far - which supplier references exist - which is exactly the part that must outlive
 * the process instance.
 */
@Entity
@Table(name = "service_order")
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA
@AllArgsConstructor(access = AccessLevel.PRIVATE) // used by the builder
public class ServiceOrder {

    @Id
    @Column(name = "id", nullable = false, length = 50)
    private String id;

    @Column(name = "external_id", length = 100)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    private ServiceOrderState state;

    @Column(name = "process_instance_id", length = 64)
    private String processInstanceId;

    @Column(name = "customer_name", nullable = false, length = 150)
    private String customerName;

    @Column(name = "customer_email", nullable = false, length = 150)
    private String customerEmail;

    @Column(name = "postcode", nullable = false, length = 20)
    private String postcode;

    @Column(name = "service_spec_id", nullable = false, length = 60)
    private String serviceSpecId;

    @Column(name = "speed_mbps")
    private Integer speedMbps;

    // Supplier references, filled in one at a time as the workflow progresses. Their presence is
    // what tells a later compensation step (Phase 4) how far the journey actually got.
    @Column(name = "supplier_customer_id", length = 64)
    private String supplierCustomerId;

    @Column(name = "supplier_subscription_id", length = 64)
    private String supplierSubscriptionId;

    @Column(name = "supplier_user_id", length = 64)
    private String supplierUserId;

    @Column(name = "phone_number", length = 32)
    private String phoneNumber;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "correlation_id", nullable = false, length = 60)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic locking, because two writers are genuinely possible: a job executor thread
     * advancing the workflow and an API request touching the same order.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Mutations are named after what happened, so the set of legal changes stays small and visible. */
    public void attachProcessInstance(String processInstanceId) {
        this.processInstanceId = processInstanceId;
        this.state = ServiceOrderState.IN_PROGRESS;
        touch();
    }

    public void customerCreated(String supplierCustomerId) {
        this.supplierCustomerId = supplierCustomerId;
        touch();
    }

    public void subscriptionCreated(String supplierSubscriptionId) {
        this.supplierSubscriptionId = supplierSubscriptionId;
        touch();
    }

    public void userCreated(String supplierUserId) {
        this.supplierUserId = supplierUserId;
        touch();
    }

    public void numberReserved(String phoneNumber) {
        this.phoneNumber = phoneNumber;
        touch();
    }

    public void completed() {
        this.state = ServiceOrderState.COMPLETED;
        this.failureReason = null;
        touch();
    }

    public void failed(String reason) {
        this.state = ServiceOrderState.FAILED;
        this.failureReason = reason;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
