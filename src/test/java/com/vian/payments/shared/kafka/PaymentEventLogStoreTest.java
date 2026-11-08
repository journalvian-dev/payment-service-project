package com.vian.payments.shared.kafka;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "app.kafka.enabled=false",
        "app.outbox.enabled=false",
        "app.reconciliation.enabled=false",
        "app.sandbox.enabled=false"
})
class PaymentEventLogStoreTest {

    @Autowired
    private PaymentEventLogStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void event_yang_sama_hanya_disimpan_sekali() throws Exception {
        UUID eventId = UUID.randomUUID();
        JsonNode payload = objectMapper.readTree("{\"status\":\"SUCCEEDED\"}");
        PaymentEventEnvelope envelope = new PaymentEventEnvelope(
                eventId,
                "payment.succeeded",
                "PAYMENT",
                UUID.randomUUID().toString(),
                Instant.parse("2026-01-14T08:15:00Z"),
                payload
        );

        assertThat(store.record(envelope)).isTrue();
        assertThat(store.record(envelope)).isFalse();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_event_log WHERE event_id = ?",
                Integer.class,
                eventId);
        assertThat(count).isEqualTo(1);
    }
}
