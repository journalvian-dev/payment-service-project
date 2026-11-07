package com.vian.payments.shared.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.vian.payments.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxStore outboxStore;

    @Mock
    private PaymentEventPublisher publisher;

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-14T08:15:00Z"), ZoneOffset.UTC);
    private final AppProperties properties = new AppProperties(
            new AppProperties.Security("api-key"),
            new AppProperties.Partner("http://localhost:8080", "client", "secret", Duration.ofSeconds(2)),
            new AppProperties.Reconciliation(true, 30_000, Duration.ofSeconds(5)),
            new AppProperties.Sandbox(false),
            new AppProperties.Outbox(true, 5_000, Duration.ofDays(7))
    );

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(outboxStore, publisher, properties, clock);
    }

    @Test
    void publish_sukses_menandai_published() {
        OutboxMessage message = message(0);
        when(outboxStore.claimBatch(anyInt())).thenReturn(List.of(message));

        relay.relay();

        verify(publisher).publish(message);
        verify(outboxStore).markPublished(message.id(), clock.instant());
        verify(outboxStore, never()).markFailed(any(), anyString(), any(), anyInt());
    }

    @Test
    void publish_gagal_menandai_retry_dengan_backoff() {
        OutboxMessage message = message(0);
        when(outboxStore.claimBatch(anyInt())).thenReturn(List.of(message));
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(message);

        relay.relay();

        ArgumentCaptor<Instant> nextAttempt = ArgumentCaptor.forClass(Instant.class);
        verify(outboxStore).markFailed(eq(message.id()), anyString(), nextAttempt.capture(), eq(10));
        assertThat(nextAttempt.getValue()).isEqualTo(clock.instant().plusSeconds(1));
        verify(outboxStore, never()).markPublished(any(), any());
    }

    @Test
    void batch_kosong_tidak_memanggil_publisher() {
        when(outboxStore.claimBatch(anyInt())).thenReturn(List.of());

        relay.relay();

        verifyNoInteractions(publisher);
        verify(outboxStore).purgePublishedBefore(clock.instant().minus(Duration.ofDays(7)));
    }

    private OutboxMessage message(int attempts) {
        return new OutboxMessage(
                UUID.randomUUID(),
                "PAYMENT",
                UUID.randomUUID().toString(),
                "payment.succeeded",
                "{}",
                attempts,
                clock.instant()
        );
    }
}
