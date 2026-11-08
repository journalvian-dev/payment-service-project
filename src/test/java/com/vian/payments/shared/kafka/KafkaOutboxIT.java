package com.vian.payments.shared.kafka;

import java.time.Instant;
import java.util.UUID;

import com.vian.payments.shared.outbox.OutboxMessage;
import com.vian.payments.shared.outbox.PaymentEventPublisher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Butuh Docker. Jalankan dengan:
 *   mvn test -Pdockertests
 *
 * Membuktikan producer -> Kafka -> consumer -> inbox store, termasuk dedupe
 * saat event yang sama dikirim dua kali.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("kafka")
class KafkaOutboxIT {

    private static final String TOPIC = "payments.events.it";
    private static final String DLT_TOPIC = "payments.events.it.DLT";
    private static final String GROUP = "payment-audit-it";

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> GROUP);
        registry.add("app.kafka.topic", () -> TOPIC);
        registry.add("app.kafka.dlt-topic", () -> DLT_TOPIC);
        registry.add("app.kafka.consumer-group", () -> GROUP);
        registry.add("app.outbox.enabled", () -> false);
        registry.add("app.reconciliation.enabled", () -> false);
        registry.add("app.sandbox.enabled", () -> false);
    }

    @Autowired
    private PaymentEventPublisher publisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void event_dipublish_dikonsumsi_dan_event_duplikat_diabaikan() throws Exception {
        UUID eventId = UUID.randomUUID();
        String paymentId = UUID.randomUUID().toString();
        String payload = """
                {"paymentId":"%s","reference":"PAY-IT","status":"SUCCEEDED","amount":100000.00}
                """.formatted(paymentId);

        OutboxMessage message = new OutboxMessage(
                eventId, "PAYMENT", paymentId, "payment.succeeded", payload, 0, Instant.now());

        publisher.publish(message);

        awaitEvent(eventId);
        assertThat(count(eventId)).isEqualTo(1);

        // Kafka at-least-once: event yang sama boleh datang lagi.
        publisher.publish(message);
        Thread.sleep(1_500);

        assertThat(count(eventId)).isEqualTo(1);
    }

    private void awaitEvent(UUID eventId) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (count(eventId) == 1) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Event tidak sampai ke consumer dalam 10 detik: " + eventId);
    }

    private int count(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_event_log WHERE event_id = ?",
                Integer.class,
                eventId);
        return count == null ? 0 : count;
    }
}
