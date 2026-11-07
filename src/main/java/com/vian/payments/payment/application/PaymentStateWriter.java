package com.vian.payments.payment.application;

import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vian.payments.payment.domain.Payment;
import com.vian.payments.payment.port.out.PaymentRepository;
import com.vian.payments.shared.outbox.OutboxMessage;
import com.vian.payments.shared.outbox.OutboxStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Menulis perubahan state payment + event outbox dalam SATU transaksi lokal.
 *
 * Ini adalah jawaban untuk masalah dual-write: kalau simpan payment lalu publish
 * ke broker secara terpisah, salah satu bisa gagal. Dengan outbox, keduanya
 * commit/rollback bersama.
 */
@Service
public class PaymentStateWriter {

    private final PaymentRepository payments;
    private final OutboxStore outboxStore;
    private final ObjectMapper objectMapper;

    public PaymentStateWriter(PaymentRepository payments,
                              OutboxStore outboxStore,
                              ObjectMapper objectMapper) {
        this.payments = payments;
        this.outboxStore = outboxStore;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Payment saveAndPublish(Payment payment) {
        Payment saved = payments.save(payment);

        String eventType = "payment." + saved.status().name().toLowerCase(Locale.ROOT);
        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID(),
                "PAYMENT",
                saved.id().toString(),
                eventType,
                writeJson(PaymentEvent.from(saved)),
                0,
                saved.updatedAt()
        );
        outboxStore.append(message);
        return saved;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Gagal serialisasi event payment", e);
        }
    }
}
