package com.vian.payments.payment.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vian.payments.shared.kafka.PaymentEventEnvelope;
import com.vian.payments.shared.kafka.PaymentEventLogStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer event payment.
 *
 * Saat ini tugasnya menulis inbox/audit log. Di production bisa ditambah
 * projection, notifikasi, atau proses downstream lain. Yang penting:
 * consumer harus idempotent karena Kafka bisa mengirim event lebih dari sekali.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class PaymentEventKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventKafkaConsumer.class);

    private final ObjectMapper objectMapper;
    private final PaymentEventLogStore logStore;

    public PaymentEventKafkaConsumer(ObjectMapper objectMapper, PaymentEventLogStore logStore) {
        this.objectMapper = objectMapper;
        this.logStore = logStore;
    }

    @KafkaListener(
            topics = "${app.kafka.topic}",
            groupId = "${app.kafka.consumer-group}"
    )
    public void consume(ConsumerRecord<String, String> record) {
        PaymentEventEnvelope event = parse(record.value());
        log.info("Kafka consume: topic={}, partition={}, offset={}, key={}, eventId={}",
                record.topic(), record.partition(), record.offset(), record.key(), event.eventId());
        logStore.record(event);
    }

    private PaymentEventEnvelope parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Payload Kafka kosong");
        }
        try {
            PaymentEventEnvelope event = objectMapper.readValue(json, PaymentEventEnvelope.class);
            if (event.eventId() == null
                    || event.eventType() == null
                    || event.aggregateType() == null
                    || event.aggregateId() == null
                    || event.occurredAt() == null) {
                throw new IllegalArgumentException("Envelope Kafka tidak lengkap");
            }
            return event;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Envelope Kafka tidak valid", e);
        }
    }
}
