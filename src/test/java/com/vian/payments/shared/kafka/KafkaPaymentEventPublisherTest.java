package com.vian.payments.shared.kafka;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vian.payments.shared.outbox.OutboxMessage;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaPaymentEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-14T08:15:00Z"), ZoneOffset.UTC);
    private final PaymentKafkaProperties properties = new PaymentKafkaProperties(
            true,
            "payments.events",
            "payments.events.DLT",
            3,
            (short) 1,
            Duration.ofSeconds(2),
            "payment-audit"
    );

    private KafkaPaymentEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new KafkaPaymentEventPublisher(kafkaTemplate, objectMapper, properties);
    }

    @Test
    void publish_mengirim_envelope_dengan_key_dan_header_event() throws Exception {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        UUID eventId = UUID.randomUUID();
        String paymentId = UUID.randomUUID().toString();
        String payload = """
                {"paymentId":"%s","status":"SUCCEEDED","amount":100000.00}
                """.formatted(paymentId);
        OutboxMessage message = new OutboxMessage(
                eventId, "PAYMENT", paymentId, "payment.succeeded", payload, 0, clock.instant());

        publisher.publish(message);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("payments.events");
        assertThat(record.key()).isEqualTo(paymentId);
        assertThat(record.headers().lastHeader("event-id")).isNotNull();
        assertThat(new String(record.headers().lastHeader("event-id").value()))
                .isEqualTo(eventId.toString());

        JsonNode envelope = objectMapper.readTree(record.value());
        assertThat(envelope.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(envelope.get("eventType").asText()).isEqualTo("payment.succeeded");
        assertThat(envelope.get("aggregateId").asText()).isEqualTo(paymentId);
        assertThat(envelope.get("payload").get("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void publish_gagal_jika_broker_error() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        OutboxMessage message = new OutboxMessage(
                UUID.randomUUID(), "PAYMENT", UUID.randomUUID().toString(),
                "payment.succeeded", "{}", 0, clock.instant());

        assertThatThrownBy(() -> publisher.publish(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Gagal publish event ke Kafka");
    }
}
