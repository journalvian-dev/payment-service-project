package com.vian.payments.payment.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vian.payments.payment.domain.Money;
import com.vian.payments.payment.domain.Payment;
import com.vian.payments.payment.domain.PaymentStatus;
import com.vian.payments.payment.port.out.PaymentRepository;
import com.vian.payments.shared.outbox.OutboxMessage;
import com.vian.payments.shared.outbox.OutboxStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentStateWriterTest {

    @Mock
    private PaymentRepository payments;

    @Mock
    private OutboxStore outboxStore;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void save_and_publish_menyimpan_payment_dan_event_outbox() {
        Payment payment = Payment.restore(
                UUID.randomUUID(), "PAY-1", "CUST-1",
                Money.of(new BigDecimal("100000.00"), "IDR"),
                "Invoice #1", PaymentStatus.SUCCEEDED, "PTR-1", null,
                Instant.parse("2026-01-14T08:15:00Z"), Instant.parse("2026-01-14T08:15:01Z")
        );
        when(payments.save(any())).thenReturn(payment);

        PaymentStateWriter writer = new PaymentStateWriter(payments, outboxStore, objectMapper);
        Payment saved = writer.saveAndPublish(payment);

        assertThat(saved).isSameAs(payment);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxStore).append(captor.capture());
        OutboxMessage message = captor.getValue();

        assertThat(message.aggregateType()).isEqualTo("PAYMENT");
        assertThat(message.aggregateId()).isEqualTo(payment.id().toString());
        assertThat(message.eventType()).isEqualTo("payment.succeeded");
        assertThat(message.payload()).contains("\"reference\":\"PAY-1\"");
        assertThat(message.payload()).contains("\"status\":\"SUCCEEDED\"");
    }
}
