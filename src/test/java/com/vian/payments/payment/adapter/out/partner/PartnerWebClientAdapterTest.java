package com.vian.payments.payment.adapter.out.partner;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.vian.payments.config.AppProperties;
import com.vian.payments.payment.adapter.out.partner.dto.PartnerChargeRequest;
import com.vian.payments.payment.port.out.PartnerChargeCommand;
import com.vian.payments.payment.port.out.PartnerPaymentResult;
import com.vian.payments.shared.hmac.HmacSigner;
import io.netty.channel.ChannelOption;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WireMock dipakai untuk mensimulasikan partner tanpa memanggil API eksternal.
 */
class PartnerWebClientAdapterTest {

    private static WireMockServer server;

    private static final Instant NOW = Instant.parse("2026-01-14T08:15:00Z");
    private static final String SECRET = "secret-key";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private PartnerWebClientAdapter adapter;
    private HmacSigner signer;

    @BeforeAll
    static void startWireMock() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopWireMock() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        server.resetAll();

        AppProperties properties = new AppProperties(
                new AppProperties.Security("api-key"),
                new AppProperties.Partner(server.baseUrl(), "client", SECRET, Duration.ofSeconds(1)),
                new AppProperties.Reconciliation(true, 30_000, Duration.ofSeconds(5)),
                new AppProperties.Sandbox(false),
                new AppProperties.Outbox(true, 5_000, Duration.ofDays(7))
        );
        signer = new HmacSigner(properties, clock);

        WebClient webClient = WebClient.builder()
                .baseUrl(server.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)))
                .build();

        PartnerHttpClient httpClient = new PartnerHttpClient(webClient, signer, properties, objectMapper, clock);
        adapter = new PartnerWebClientAdapter(httpClient);
    }

    @Test
    void charge_mengirim_hmac_dan_idempotency_key_yang_benar() throws Exception {
        server.stubFor(post(urlEqualTo("/sandbox/partner/payments"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"partnerReference\":\"PTR-1\",\"status\":\"SUCCEEDED\"}")));

        PartnerChargeCommand command = new PartnerChargeCommand(
                "PAY-TEST-1", new BigDecimal("100000.00"), "IDR", "CUST-1");

        PartnerPaymentResult result = adapter.charge(command);

        assertThat(result.status()).isEqualTo(PartnerPaymentResult.Status.SUCCEEDED);
        assertThat(result.partnerReference()).isEqualTo("PTR-1");

        String body = objectMapper.writeValueAsString(new PartnerChargeRequest(
                command.reference(), command.amount(), command.currency(), command.customerId()));
        String expectedSignature = signer.sign("POST", "/sandbox/partner/payments", NOW.getEpochSecond(), body);

        server.verify(postRequestedFor(urlEqualTo("/sandbox/partner/payments"))
                .withHeader("Idempotency-Key", equalTo("PAY-TEST-1"))
                .withHeader("X-Client-Id", equalTo("client"))
                .withHeader("X-Timestamp", equalTo(String.valueOf(NOW.getEpochSecond())))
                .withHeader("X-Signature", equalTo("v1=" + expectedSignature)));
    }

    @Test
    void charge_402_dipetakan_menjadi_declined() {
        server.stubFor(post(urlEqualTo("/sandbox/partner/payments"))
                .willReturn(aResponse()
                        .withStatus(402)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"DECLINED\",\"reason\":\"INSUFFICIENT_FUNDS\"}")));

        PartnerPaymentResult result = adapter.charge(
                new PartnerChargeCommand("PAY-TEST-2", new BigDecimal("1000.00"), "IDR", "CUST-1"));

        assertThat(result.status()).isEqualTo(PartnerPaymentResult.Status.DECLINED);
        assertThat(result.reason()).contains("HTTP 402");
    }

    @Test
    void charge_500_dilempar_ke_circuit_breaker_untuk_dipetakan_unknown() {
        server.stubFor(post(urlEqualTo("/sandbox/partner/payments"))
                .willReturn(aResponse().withStatus(500)));

        // Tanpa proxy Spring, circuit breaker tidak aktif; adapter melempar exception.
        // Di aplikasi nyata, fallback mengubahnya menjadi UNKNOWN -> payment IN_DOUBT.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.charge(
                        new PartnerChargeCommand("PAY-TEST-3", new BigDecimal("1000.00"), "IDR", "CUST-1")))
                .hasMessageContaining("500");
    }
}
