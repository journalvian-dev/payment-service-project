package com.vian.payments.payment.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root. Semua transisi state ada di sini, bukan di service.
 *
 * PENDING -> SUCCEEDED | FAILED | IN_DOUBT
 * IN_DOUBT -> SUCCEEDED | FAILED   (lewat reconciliation / webhook)
 * SUCCEEDED / FAILED = terminal (first terminal state wins)
 */
public class Payment {

    private final UUID id;
    private final String reference;
    private final String customerId;
    private final Money money;
    private final String description;
    private final Instant createdAt;

    private PaymentStatus status;
    private String partnerReference;
    private String failureReason;
    private Instant updatedAt;

    private Payment(UUID id,
                    String reference,
                    String customerId,
                    Money money,
                    String description,
                    PaymentStatus status,
                    String partnerReference,
                    String failureReason,
                    Instant createdAt,
                    Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.reference = Objects.requireNonNull(reference, "reference");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.money = Objects.requireNonNull(money, "money");
        this.description = description;
        this.status = Objects.requireNonNull(status, "status");
        this.partnerReference = partnerReference;
        this.failureReason = failureReason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Payment newPending(String reference,
                                     String customerId,
                                     Money money,
                                     String description,
                                     Instant now) {
        return new Payment(UUID.randomUUID(), reference, customerId, money, description,
                PaymentStatus.PENDING, null, null, now, now);
    }

    /**
     * Dipakai adapter persistence saat rehidrasi dari DB. Tidak untuk business flow.
     */
    public static Payment restore(UUID id,
                                  String reference,
                                  String customerId,
                                  Money money,
                                  String description,
                                  PaymentStatus status,
                                  String partnerReference,
                                  String failureReason,
                                  Instant createdAt,
                                  Instant updatedAt) {
        return new Payment(id, reference, customerId, money, description, status,
                partnerReference, failureReason, createdAt, updatedAt);
    }

    public boolean markSucceeded(String partnerReference) {
        if (status.isFinal()) {
            return false; // idempotent: webhook/reconciliation boleh datang lebih dari sekali
        }
        this.status = PaymentStatus.SUCCEEDED;
        this.partnerReference = partnerReference;
        this.failureReason = null;
        this.updatedAt = Instant.now();
        return true;
    }

    public boolean markFailed(String reason) {
        if (status.isFinal()) {
            return false; // first terminal state wins; discrepancy dicatat reconciliation
        }
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = Instant.now();
        return true;
    }

    public boolean markInDoubt(String reason) {
        if (status != PaymentStatus.PENDING) {
            return false;
        }
        this.status = PaymentStatus.IN_DOUBT;
        this.failureReason = reason;
        this.updatedAt = Instant.now();
        return true;
    }

    public boolean isFinal() {
        return status.isFinal();
    }

    public UUID id() {
        return id;
    }

    public String reference() {
        return reference;
    }

    public String customerId() {
        return customerId;
    }

    public Money money() {
        return money;
    }

    public String description() {
        return description;
    }

    public PaymentStatus status() {
        return status;
    }

    public String partnerReference() {
        return partnerReference;
    }

    public String failureReason() {
        return failureReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
