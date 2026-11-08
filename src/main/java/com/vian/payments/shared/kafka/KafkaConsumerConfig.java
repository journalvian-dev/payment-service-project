package com.vian.payments.shared.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Retry 2x lalu kirim ke DLT.
 *
 * Pesan yang formatnya invalid (IllegalArgumentException) langsung ke DLT,
 * karena retry tidak akan pernah membuat JSON rusak menjadi valid.
 */
@Configuration
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                 PaymentKafkaProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) ->
                        new TopicPartition(properties.dltTopic(), record.partition())
        );

        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(1_000L, 2L)
        );
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
