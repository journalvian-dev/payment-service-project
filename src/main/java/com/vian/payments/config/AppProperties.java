package com.vian.payments.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Semua konfigurasi aplikasi di-bind ke satu record immutable.
 * Tidak ada @Value berserakan di service -> lebih mudah di-test.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Security security,
        Partner partner,
        Reconciliation reconciliation,
        Sandbox sandbox,
        Outbox outbox
) {

    public record Security(String apiKey) {
    }

    public record Partner(
            String baseUrl,
            String clientId,
            String secret,
            Duration timeout
    ) {
    }

    public record Reconciliation(
            boolean enabled,
            long intervalMs,
            Duration inDoubtAfter
    ) {
    }

    public record Sandbox(@DefaultValue("true") boolean enabled) {
    }

    public record Outbox(
            @DefaultValue("true") boolean enabled,
            long intervalMs,
            @DefaultValue("7d") Duration retention
    ) {
    }
}
