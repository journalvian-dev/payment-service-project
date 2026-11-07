package com.vian.payments.shared.hmac;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.vian.payments.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSignerTest {

    private static final Instant NOW = Instant.parse("2026-01-14T08:15:00Z");

    private final AppProperties properties = new AppProperties(
            new AppProperties.Security("api-key"),
            new AppProperties.Partner("http://localhost:8080", "client", "secret-key", Duration.ofSeconds(2)),
            new AppProperties.Reconciliation(true, 30_000, Duration.ofSeconds(5)),
            new AppProperties.Sandbox(true),
            new AppProperties.Outbox(true, 5_000, Duration.ofDays(7))
    );

    private final HmacSigner signer = new HmacSigner(
            properties,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void signature_deterministik_dan_berubah_jika_body_berubah() {
        long ts = NOW.getEpochSecond();
        String first = signer.sign("POST", "/api/v1/payments", ts, "{\"a\":1}");
        String second = signer.sign("POST", "/api/v1/payments", ts, "{\"a\":1}");
        String different = signer.sign("POST", "/api/v1/payments", ts, "{\"a\":2}");

        assertThat(first).isEqualTo(second).hasSize(64);
        assertThat(first).isNotEqualTo(different);
    }

    @Test
    void verify_menerima_signature_valid_dengan_prefix_v1() {
        long ts = NOW.getEpochSecond();
        String signature = signer.sign("POST", "/webhook", ts, "{}");

        assertThat(signer.verify("POST", "/webhook", ts, "{}", "v1=" + signature)).isTrue();
        assertThat(signer.verify("POST", "/webhook", ts, "{}", signature)).isTrue();
    }

    @Test
    void verify_menolak_body_timestamp_dan_signature_yang_salah() {
        long ts = NOW.getEpochSecond();
        String signature = signer.sign("POST", "/webhook", ts, "{}");

        assertThat(signer.verify("POST", "/webhook", ts, "{\"tampered\":true}", signature)).isFalse();
        assertThat(signer.verify("POST", "/webhook", ts, "{}", "deadbeef")).isFalse();
        assertThat(signer.verify("POST", "/webhook", ts - 301, "{}", signature)).isFalse();
        assertThat(signer.verify("POST", "/webhook", ts, "{}", null)).isFalse();
    }
}
