package com.vian.payments.payment.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vian.payments.payment.domain.Money;
import com.vian.payments.payment.domain.Payment;
import com.vian.payments.payment.domain.PaymentNotFoundException;
import com.vian.payments.payment.port.in.CreatePaymentUseCase;
import com.vian.payments.payment.port.in.GetPaymentUseCase;
import com.vian.payments.payment.port.in.HandlePartnerWebhookUseCase;
import com.vian.payments.payment.port.in.PartnerWebhookEvent;
import com.vian.payments.payment.port.out.PartnerChargeCommand;
import com.vian.payments.payment.port.out.PartnerClient;
import com.vian.payments.payment.port.out.PartnerPaymentResult;
import com.vian.payments.payment.port.out.PaymentRepository;
import com.vian.payments.shared.idempotency.IdempotencyConflictException;
import com.vian.payments.shared.idempotency.IdempotencyDecision;
import com.vian.payments.shared.idempotency.IdempotencyStore;
import com.vian.payments.shared.idempotency.RequestInProgressException;
import com.vian.payments.shared.webhook.WebhookEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Application service: orkestrasi use case.
 *
 * Catatan transaksi:
 * - TIDAK memakai @Transactional di sekitar panggilan HTTP partner.
 *   Menahan koneksi DB selama HTTP call adalah penyebab klasik connection pool habis.
 * - Setiap save() punya transaksi sendiri (short transaction).
 * - State final disimpan lewat PaymentStateWriter supaya event outbox ikut
 *   commit/rollback bersama perubahan payment.
 */
@Service
public class PaymentApplicationService implements CreatePaymentUseCase, GetPaymentUseCase, HandlePartnerWebhookUseCase {

    private static final Logger log = LoggerFactory.getLogger(PaymentApplicationService.class);
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final PaymentRepository payments;
    private final PaymentStateWriter paymentStateWriter;
    private final PartnerClient partnerClient;
    private final IdempotencyStore idempotencyStore;
    private final WebhookEventStore webhookEventStore;
    private final PaymentReferenceGenerator referenceGenerator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PaymentApplicationService(PaymentRepository payments,
                                     PaymentStateWriter paymentStateWriter,
                                     PartnerClient partnerClient,
                                     IdempotencyStore idempotencyStore,
                                     WebhookEventStore webhookEventStore,
                                     PaymentReferenceGenerator referenceGenerator,
                                     ObjectMapper objectMapper,
                                     Clock clock) {
        this.payments = payments;
        this.paymentStateWriter = paymentStateWriter;
        this.partnerClient = partnerClient;
        this.idempotencyStore = idempotencyStore;
        this.webhookEventStore = webhookEventStore;
        this.referenceGenerator = referenceGenerator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @CacheEvict(cacheNames = "payment-by-id", allEntries = true)
    public PaymentResult create(String idempotencyKey, CreatePaymentCommand command) {
        validateKey(idempotencyKey);
        validateCommand(command);

        String requestHash = sha256(writeJson(command));
        IdempotencyDecision decision = idempotencyStore.begin(idempotencyKey, requestHash);

        if (decision instanceof IdempotencyDecision.Proceed) {
            return process(idempotencyKey, command);
        }
        if (decision instanceof IdempotencyDecision.Replay replay) {
            return readReplay(replay.responseBody());
        }
        if (decision instanceof IdempotencyDecision.InProgress) {
            throw new RequestInProgressException(idempotencyKey);
        }
        if (decision instanceof IdempotencyDecision.Conflict conflict) {
            throw new IdempotencyConflictException(conflict.message());
        }
        throw new IllegalStateException("Decision tidak dikenal: " + decision);
    }

    private PaymentResult process(String idempotencyKey, CreatePaymentCommand command) {
        Instant now = clock.instant();
        Money money = Money.of(command.amount(), command.currency());

        Payment payment = Payment.newPending(
                referenceGenerator.next(),
                command.customerId(),
                money,
                command.description(),
                now
        );
        payments.save(payment);

        PartnerPaymentResult partnerResult;
        try {
            partnerResult = partnerClient.charge(new PartnerChargeCommand(
                    payment.reference(),
                    money.amount(),
                    money.currency(),
                    command.customerId()
            ));
        } catch (RuntimeException e) {
            // Defense in depth. Adapter seharusnya sudah mapping error infrastruktur ke UNKNOWN.
            log.warn("Partner call melempar exception tak terduga untuk {}", payment.reference(), e);
            partnerResult = PartnerPaymentResult.unknown(e.getMessage());
        }

        apply(payment, partnerResult);
        // Perubahan state final + event outbox ditulis atomik dalam satu transaksi.
        paymentStateWriter.saveAndPublish(payment);

        PaymentResult view = toResult(payment, false);
        idempotencyStore.complete(idempotencyKey, writeJson(view));
        return view;
    }

    private void apply(Payment payment, PartnerPaymentResult partnerResult) {
        switch (partnerResult.status()) {
            case SUCCEEDED -> payment.markSucceeded(partnerResult.partnerReference());
            case DECLINED -> payment.markFailed(
                    partnerResult.reason() == null ? "DECLINED" : partnerResult.reason());
            case UNKNOWN -> payment.markInDoubt(
                    partnerResult.reason() == null ? "UNKNOWN" : partnerResult.reason());
        }
        log.info("Payment {} -> {} (partnerRef={}, reason={})",
                payment.reference(), payment.status(),
                payment.partnerReference(), payment.failureReason());
    }

    @Override
    @Cacheable(cacheNames = "payment-by-id", key = "#id")
    public PaymentResult getById(java.util.UUID id) {
        return payments.findById(id)
                .map(payment -> toResult(payment, false))
                .orElseThrow(() -> PaymentNotFoundException.byId(id));
    }

    @Override
    @CacheEvict(cacheNames = "payment-by-id", allEntries = true)
    public void handle(PartnerWebhookEvent event) {
        if (event.eventId() == null || event.eventId().isBlank()) {
            throw new IllegalArgumentException("eventId webhook wajib ada");
        }
        if (event.reference() == null || event.reference().isBlank()) {
            throw new IllegalArgumentException("reference webhook wajib ada");
        }

        Payment payment = payments.findByReference(event.reference())
                .orElseThrow(() -> new PaymentNotFoundException(
                        "Payment dengan reference " + event.reference() + " tidak ditemukan"));

        if (!webhookEventStore.register(event.eventId(), "payment." + event.status())) {
            log.info("Webhook duplikat diabaikan: eventId={}", event.eventId());
            return;
        }

        String status = event.status() == null ? "" : event.status().toUpperCase(Locale.ROOT);
        boolean changed = switch (status) {
            case "SUCCEEDED" -> payment.markSucceeded(event.partnerReference());
            case "FAILED" -> payment.markFailed(
                    event.reason() == null ? "FAILED_BY_PARTNER" : event.reason());
            default -> {
                log.warn("Status webhook tidak dikenal: {} (eventId={})", event.status(), event.eventId());
                yield false;
            }
        };

        if (changed) {
            paymentStateWriter.saveAndPublish(payment);
            log.info("Webhook mengubah payment {} -> {}", payment.reference(), payment.status());
        }
    }

    private PaymentResult readReplay(String responseBody) {
        try {
            return objectMapper.readValue(responseBody, PaymentResult.class).withReplayed(true);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Response idempotency tersimpan tidak valid", e);
        }
    }

    private PaymentResult toResult(Payment payment, boolean replayed) {
        return new PaymentResult(
                payment.id(),
                payment.reference(),
                payment.customerId(),
                payment.money().amount(),
                payment.money().currency(),
                payment.status().name(),
                payment.partnerReference(),
                payment.failureReason(),
                payment.createdAt(),
                replayed
        );
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Header Idempotency-Key wajib diisi");
        }
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key maksimal " + MAX_IDEMPOTENCY_KEY_LENGTH + " karakter");
        }
    }

    private void validateCommand(CreatePaymentCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Body request wajib diisi");
        }
        if (command.customerId() == null || command.customerId().isBlank()) {
            throw new IllegalArgumentException("customerId wajib diisi");
        }
        if (command.amount() == null) {
            throw new IllegalArgumentException("amount wajib diisi");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Gagal serialisasi JSON", e);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 tidak tersedia", e);
        }
    }
}
