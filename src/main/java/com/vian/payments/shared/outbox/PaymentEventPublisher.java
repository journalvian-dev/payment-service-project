package com.vian.payments.shared.outbox;

/**
 * Port keluar untuk publish event. Di production implementasinya bisa Kafka,
 * RabbitMQ, SNS, atau HTTP ke partner.
 */
public interface PaymentEventPublisher {

    void publish(OutboxMessage message);
}
