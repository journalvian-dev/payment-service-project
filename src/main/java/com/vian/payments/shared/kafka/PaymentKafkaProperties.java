package com.vian.payments.shared.kafka;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Konfigurasi Kafka yang dipakai publisher, consumer, dan topic admin.
 * Semua override-able lewat env var / application-kafka.yml.
 */
@ConfigurationProperties(prefix = "app.kafka")
public record PaymentKafkaProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("payments.events") String topic,
        @DefaultValue("payments.events.DLT") String dltTopic,
        @DefaultValue("3") int partitions,
        @DefaultValue("1") short replicas,
        @DefaultValue("10s") Duration sendTimeout,
        @DefaultValue("payment-audit") String consumerGroup
) {
}
