package com.vian.payments.payment.adapter.out.persistence;

import java.math.BigDecimal;
import java.util.UUID;

import com.vian.payments.payment.domain.Money;
import com.vian.payments.payment.domain.Payment;
import com.vian.payments.payment.domain.PaymentStatus;
import com.vian.payments.payment.port.out.PaymentRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test melawan PostgreSQL asli (bukan H2) -> catch perbedaan SQL/dialect.
 *
 * Butuh Docker. Jalankan dengan: mvn test -Pdockertests
 * Tanpa Docker, test otomatis di-skip.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("postgres")
class PaymentPersistencePostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("payments")
                    .withUsername("payments")
                    .withPassword("payments");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.reconciliation.enabled", () -> "false");
    }

    @Autowired
    private PaymentRepository payments;

    @Test
    void save_find_dan_update_status_di_postgres() {
        Payment payment = Payment.newPending(
                "PAY-PG-" + UUID.randomUUID(),
                "CUST-PG",
                Money.of(new BigDecimal("250000.00"), "IDR"),
                "Postgres integration test",
                java.time.Instant.now()
        );

        payments.save(payment);

        Payment loaded = payments.findById(payment.id()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(loaded.money().amount()).isEqualByComparingTo("250000.00");

        loaded.markInDoubt("timeout simulasi");
        payments.save(loaded);

        Payment updated = payments.findById(payment.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(PaymentStatus.IN_DOUBT);
        assertThat(payments.findByReference(loaded.reference())).isPresent();
    }
}
