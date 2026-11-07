package com.vian.payments.payment.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.vian.payments.payment.domain.Money;
import com.vian.payments.payment.domain.Payment;
import com.vian.payments.payment.domain.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Entity JPA murni untuk persistence. Tidak bocor ke domain.
 * UUID di-assign aplikasi (bukan generated) supaya id bisa dipakai sebelum flush.
 */
@Entity
@Table(name = "payments")
public class PaymentJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 64)
    private String reference;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "partner_reference", length = 64)
    private String partnerReference;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected PaymentJpaEntity() {
        // for JPA
    }

    public PaymentJpaEntity(UUID id) {
        this.id = id;
    }

    public void apply(Payment payment) {
        this.id = payment.id();
        this.reference = payment.reference();
        this.customerId = payment.customerId();
        this.amount = payment.money().amount();
        this.currency = payment.money().currency();
        this.description = payment.description();
        this.status = payment.status();
        this.partnerReference = payment.partnerReference();
        this.failureReason = payment.failureReason();
        this.createdAt = payment.createdAt();
        this.updatedAt = payment.updatedAt();
    }

    public Payment toDomain() {
        return Payment.restore(
                id,
                reference,
                customerId,
                Money.of(amount, currency),
                description,
                status,
                partnerReference,
                failureReason,
                createdAt,
                updatedAt
        );
    }

    public UUID getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
