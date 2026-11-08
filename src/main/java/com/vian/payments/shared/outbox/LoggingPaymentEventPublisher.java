package com.vian.payments.shared.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Publisher demo: cukup log. Aktif kalau Kafka tidak diaktifkan.
 * Ganti dengan Kafka/Rabbit/HTTP publisher tanpa mengubah domain atau application service.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingPaymentEventPublisher implements PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingPaymentEventPublisher.class);

    @Override
    public void publish(OutboxMessage message) {
        log.info("OUTBOX PUBLISH id={} type={} aggregate={}:{} attempts={} payload={}",
                message.id(),
                message.eventType(),
                message.aggregateType(),
                message.aggregateId(),
                message.attempts(),
                message.payload());
    }
}
