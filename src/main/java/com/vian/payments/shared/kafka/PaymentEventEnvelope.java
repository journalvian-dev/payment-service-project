package com.vian.payments.shared.kafka;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Envelope event Kafka.
 *
 * Payload bisnis tetap di dalam field payload, sementara metadata event
 * (eventId, eventType, aggregateId) ada di level atas supaya consumer bisa
 * melakukan deduplikasi dan routing tanpa membuka payload bisnis.
 */
public record PaymentEventEnvelope(
        UUID eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        Instant occurredAt,
        JsonNode payload
) {
}
