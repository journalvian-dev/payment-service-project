package com.vian.payments.payment.adapter.in.messaging;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vian.payments.shared.kafka.PaymentEventLogStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentEventKafkaConsumerTest {

    @Mock
    private PaymentEventLogStore store;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private PaymentEventKafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentEventKafkaConsumer(objectMapper, store);
    }

    @Test
    void consume_envelope_valid_meneruskan_ke_store() {
        UUID eventId = UUID.randomUUID();
        String json = """
                {
                  "eventId": "%s",
                  "eventType": "payment.succeeded",
                  "aggregateType": "PAYMENT",
                  "aggregateId": "%s",
                  "occurredAt": "%s",
                  "payload": {"status":"SUCCEEDED"}
                }
                """.formatted(eventId, UUID.randomUUID(), Instant.now());

        consumer.consume(new ConsumerRecord<>("payments.events", 0, 10L, "key", json));

        verify(store).record(any());
    }

    @Test
    void consume_payload_rusak_langsung_gagal_tanpa_retry() {
        assertThatThrownBy(() ->
                consumer.consume(new ConsumerRecord<>("payments.events", 0, 11L, "key", "{not-json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Envelope Kafka tidak valid");

        verifyNoInteractions(store);
    }
}
