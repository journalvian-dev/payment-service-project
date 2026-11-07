package com.vian.payments.payment.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vian.payments.payment.domain.Payment;
import com.vian.payments.payment.domain.PaymentStatus;
import com.vian.payments.payment.port.in.PartnerWebhookEvent;
import com.vian.payments.payment.port.out.PartnerClient;
import com.vian.payments.payment.port.out.PartnerPaymentResult;
import com.vian.payments.payment.port.out.PaymentRepository;
import com.vian.payments.shared.idempotency.IdempotencyConflictException;
import com.vian.payments.shared.idempotency.IdempotencyDecision;
import com.vian.payments.shared.idempotency.IdempotencyStore;
import com.vian.payments.shared.idempotency.RequestInProgressException;
import com.vian.payments.shared.webhook.WebhookEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentApplicationServiceTest {

    @Mock
    private PaymentRepository payments;

    @Mock
    private PaymentStateWriter paymentStateWriter;

    @Mock
    private PartnerClient partnerClient;

    @Mock
    private IdempotencyStore idempotencyStore;

    @Mock
    private WebhookEventStore webhookEventStore;

    @Mock
    private PaymentReferenceGenerator referenceGenerator;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-14T08:15:00Z"), ZoneOffset.UTC);

    private PaymentApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PaymentApplicationService(
                payments,
                paymentStateWriter,
                partnerClient,
                idempotencyStore,
                webhookEventStore,
                referenceGenerator,
                objectMapper,
                clock
        );
        when(referenceGenerator.next()).thenReturn("PAY-TEST-000001");
        when(payments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentStateWriter.saveAndPublish(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void create_success_menyimpan_payment_dan_response_idempotency() {
        when(idempotencyStore.begin(anyString(), anyString())).thenReturn(new IdempotencyDecision.Proceed());
        when(partnerClient.charge(any())).thenReturn(PartnerPaymentResult.approved("PTR-1"));

        PaymentResult result = service.create("key-1", command());

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.partnerReference()).isEqualTo("PTR-1");
        assertThat(result.replayed()).isFalse();
        assertThat(result.reference()).isEqualTo("PAY-TEST-000001");

        verify(payments).save(any());
        verify(paymentStateWriter).saveAndPublish(any());
        verify(idempotencyStore).complete(eq("key-1"), anyString());
    }

    @Test
    void create_partner_unknown_menandai_in_doubt_tanpa_retry_manual() {
        when(idempotencyStore.begin(anyString(), anyString())).thenReturn(new IdempotencyDecision.Proceed());
        when(partnerClient.charge(any())).thenReturn(PartnerPaymentResult.unknown("timeout"));

        PaymentResult result = service.create("key-2", command());

        assertThat(result.status()).isEqualTo("IN_DOUBT");
        assertThat(result.failureReason()).contains("timeout");
        verify(idempotencyStore).complete(eq("key-2"), anyString());
    }

    @Test
    void create_partner_decline_menandai_failed() {
        when(idempotencyStore.begin(anyString(), anyString())).thenReturn(new IdempotencyDecision.Proceed());
        when(partnerClient.charge(any())).thenReturn(PartnerPaymentResult.declined("INSUFFICIENT_FUNDS"));

        PaymentResult result = service.create("key-3", command());

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failureReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void create_replay_mengembalikan_response_tersimpan_tanpa_memanggil_partner() throws Exception {
        PaymentResult stored = new PaymentResult(
                UUID.randomUUID(), "PAY-OLD", "CUST-1", new BigDecimal("100000.00"), "IDR",
                "SUCCEEDED", "PTR-OLD", null, clock.instant(), false
        );
        when(idempotencyStore.begin(anyString(), anyString()))
                .thenReturn(new IdempotencyDecision.Replay(objectMapper.writeValueAsString(stored)));

        PaymentResult result = service.create("key-4", command());

        assertThat(result.replayed()).isTrue();
        assertThat(result.reference()).isEqualTo("PAY-OLD");
        verifyNoInteractions(partnerClient);
        verify(idempotencyStore, never()).complete(anyString(), anyString());
    }

    @Test
    void create_hash_mismatch_menolak_request() {
        when(idempotencyStore.begin(anyString(), anyString()))
                .thenReturn(new IdempotencyDecision.Conflict("body berbeda"));

        assertThatThrownBy(() -> service.create("key-5", command()))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("body berbeda");

        verifyNoInteractions(partnerClient);
    }

    @Test
    void create_request_masih_proses_menolak_request() {
        when(idempotencyStore.begin(anyString(), anyString()))
                .thenReturn(new IdempotencyDecision.InProgress());

        assertThatThrownBy(() -> service.create("key-6", command()))
                .isInstanceOf(RequestInProgressException.class);

        verifyNoInteractions(partnerClient);
    }

    @Test
    void webhook_succeeded_mengubah_payment_in_doubt() {
        Payment payment = Payment.restore(
                UUID.randomUUID(), "PAY-1", "CUST-1",
                com.vian.payments.payment.domain.Money.of(new BigDecimal("1000.00"), "IDR"),
                null, PaymentStatus.IN_DOUBT, null, "timeout", clock.instant(), clock.instant()
        );
        when(payments.findByReference("PAY-1")).thenReturn(Optional.of(payment));
        when(webhookEventStore.register("evt-1", "payment.SUCCEEDED")).thenReturn(true);

        service.handle(new PartnerWebhookEvent("evt-1", "PAY-1", "SUCCEEDED", "PTR-9", null));

        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.partnerReference()).isEqualTo("PTR-9");
        verify(paymentStateWriter).saveAndPublish(payment);
    }

    @Test
    void webhook_duplikat_diabaikan() {
        Payment payment = Payment.restore(
                UUID.randomUUID(), "PAY-2", "CUST-1",
                com.vian.payments.payment.domain.Money.of(new BigDecimal("1000.00"), "IDR"),
                null, PaymentStatus.PENDING, null, null, clock.instant(), clock.instant()
        );
        when(payments.findByReference("PAY-2")).thenReturn(Optional.of(payment));
        when(webhookEventStore.register("evt-2", "payment.SUCCEEDED")).thenReturn(false);

        service.handle(new PartnerWebhookEvent("evt-2", "PAY-2", "SUCCEEDED", "PTR-9", null));

        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        verify(payments, never()).save(any());
        verify(paymentStateWriter, never()).saveAndPublish(any());
    }

    private CreatePaymentCommand command() {
        return new CreatePaymentCommand("CUST-1", new BigDecimal("100000.00"), "IDR", "Invoice #1");
    }
}
