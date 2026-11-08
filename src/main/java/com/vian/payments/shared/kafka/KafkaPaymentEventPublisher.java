package com.vian.payments.shared.kafka;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vian.payments.shared.outbox.OutboxMessage;
import com.vian.payments.shared.outbox.PaymentEventPublisher;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publisher Kafka untuk outbox.
 *
 * OutboxRelay memanggil publish(), lalu menandai event PUBLISHED hanya kalau
 * Kafka sudah meng-ack send tersebut. Kalau broker down, exception dilempar
 * dan relay akan retry dengan backoff.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaPaymentEventPublisher implements PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaPaymentEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final PaymentKafkaProperties properties;

    public KafkaPaymentEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                      ObjectMapper objectMapper,
                                      PaymentKafkaProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void publish(OutboxMessage message) {
        try {
            PaymentEventEnvelope envelope = new PaymentEventEnvelope(
                    message.id(),
                    message.eventType(),
                    message.aggregateType(),
                    message.aggregateId(),
                    message.createdAt(),
                    objectMapper.readTree(message.payload())
            );

            ProducerRecord<String, String> record = new ProducerRecord<>(
                    properties.topic(),
                    message.aggregateId(),
                    objectMapper.writeValueAsString(envelope)
            );
            record.headers().add("event-id", message.id().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add("event-type", message.eventType().getBytes(StandardCharsets.UTF_8));
            record.headers().add("aggregate-id", message.aggregateId().getBytes(StandardCharsets.UTF_8));

            // Tunggu ack broker. Ini membuat relay tidak menandai PUBLISHED
            // sebelum event benar-benar diterima Kafka.
            kafkaTemplate.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            log.info("Kafka publish sukses: eventId={}, type={}, key={}",
                    message.id(), message.eventType(), message.aggregateId());
        } catch (Exception e) {
            throw new IllegalStateException("Gagal publish event ke Kafka: " + message.id(), e);
        }
    }
}
