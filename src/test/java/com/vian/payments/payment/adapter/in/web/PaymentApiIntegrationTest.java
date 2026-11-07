package com.vian.payments.payment.adapter.in.web;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.vian.payments.payment.port.in.ReconcilePaymentsUseCase;
import com.vian.payments.shared.hmac.HmacSigner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test end-to-end lewat HTTP layer, Spring Security, HMAC, JPA, Flyway.
 * Partner eksternal diganti WireMock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.security.api-key=test-key",
        "app.partner.client-id=test-client",
        "app.partner.secret=test-secret",
        "app.partner.timeout=500ms",
        "app.reconciliation.enabled=false",
        "app.reconciliation.in-doubt-after=0s",
        // Relay tidak perlu jalan di test API; kita hanya memverifikasi event ikut tersimpan.
        "app.outbox.enabled=false",
        // Jangan sampai circuit breaker terbuka karena akumulasi skenario gagal antar test.
        "resilience4j.circuitbreaker.instances.partner.minimumNumberOfCalls=1000",
        "resilience4j.circuitbreaker.instances.partner.slidingWindowSize=1000"
})
class PaymentApiIntegrationTest {

    private static final String API_KEY = "test-key";
    private static final String PARTNER_PATH = "/sandbox/partner/payments";

    private static final WireMockServer PARTNER = new WireMockServer(options().dynamicPort());

    static {
        PARTNER.start();
    }

    @DynamicPropertySource
    static void partnerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.partner.base-url", PARTNER::baseUrl);
    }

    @AfterAll
    static void stopPartner() {
        PARTNER.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private HmacSigner hmacSigner;

    @Autowired
    private ReconcilePaymentsUseCase reconcilePaymentsUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetPartner() {
        PARTNER.resetAll();
    }

    @Test
    void create_success_lalu_replay_dengan_idempotency_key_yang_sama() throws Exception {
        stubPartnerSuccess("PTR-1");

        String key = "key-" + UUID.randomUUID();
        String body = createBody();

        MvcResult first = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                        .header("X-Api-Key", API_KEY)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.partnerReference").value("PTR-1"))
                .andReturn();

        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());

        MvcResult replay = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                        .header("X-Api-Key", API_KEY)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        JsonNode replayJson = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(replayJson.get("id").asText()).isEqualTo(firstJson.get("id").asText());

        // Partner hanya boleh dipanggil sekali.
        PARTNER.verify(1, postRequestedFor(urlEqualTo(PARTNER_PATH)));

        // Outbox ditulis di transaksi yang sama dengan state final payment.
        Integer outboxEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                Integer.class,
                firstJson.get("id").asText(),
                "payment.succeeded");
        assertThat(outboxEvents).isEqualTo(1);
    }

    @Test
    void idempotency_key_dipakai_body_berbeda_menghasilkan_409() throws Exception {
        stubPartnerSuccess("PTR-2");

        String key = "key-" + UUID.randomUUID();

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                        .header("X-Api-Key", API_KEY)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated());

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                        .header("X-Api-Key", API_KEY)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody().replace("100000.00", "999.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void tanpa_api_key_menghasilkan_401() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void partner_500_menghasilkan_payment_in_doubt() throws Exception {
        PARTNER.stubFor(post(urlEqualTo(PARTNER_PATH)).willReturn(aResponse().withStatus(500)));

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                        .header("X-Api-Key", API_KEY)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN_DOUBT"));
    }

    @Test
    void webhook_signed_menyelesaikan_payment_in_doubt_dan_duplikat_diabaikan() throws Exception {
        PARTNER.stubFor(post(urlEqualTo(PARTNER_PATH)).willReturn(aResponse().withStatus(500)));

        MvcResult created = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                        .header("X-Api-Key", API_KEY)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN_DOUBT"))
                .andReturn();

        JsonNode payment = objectMapper.readTree(created.getResponse().getContentAsString());

        String payload = objectMapper.writeValueAsString(Map.of(
                "eventId", "evt-" + UUID.randomUUID(),
                "reference", payment.get("reference").asText(),
                "status", "SUCCEEDED",
                "partnerReference", "PTR-WEBHOOK"
        ));
        long timestamp = Instant.now().getEpochSecond();
        String signature = hmacSigner.sign("POST", "/api/v1/webhooks/partner", timestamp, payload);

        sendWebhook(payload, timestamp, signature)
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/payments/{id}", payment.get("id").asText())
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.partnerReference").value("PTR-WEBHOOK"));

        // Webhook yang sama dikirim ulang: harus 200 dan tidak mengubah apa pun.
        sendWebhook(payload, timestamp, signature).andExpect(status().isOk());
    }

    @Test
    void webhook_dengan_signature_salah_ditolak() throws Exception {
        sendWebhook("{\"eventId\":\"evt-x\",\"reference\":\"PAY-X\",\"status\":\"SUCCEEDED\"}",
                Instant.now().getEpochSecond(), "v1=deadbeef")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
    }

    @Test
    void reconciliation_menyelesaikan_payment_in_doubt() throws Exception {
        PARTNER.stubFor(post(urlEqualTo(PARTNER_PATH)).willReturn(aResponse().withStatus(500)));

        MvcResult created = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/payments")
                        .header("X-Api-Key", API_KEY)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN_DOUBT"))
                .andReturn();

        JsonNode payment = objectMapper.readTree(created.getResponse().getContentAsString());

        PARTNER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                        urlPathMatching("/sandbox/partner/payments/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"partnerReference\":\"PTR-RECON\",\"status\":\"SUCCEEDED\"}")));

        int resolved = reconcilePaymentsUseCase.reconcile();
        assertThat(resolved).isGreaterThanOrEqualTo(1);

        mockMvc.perform(get("/api/v1/payments/{id}", payment.get("id").asText())
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.partnerReference").value("PTR-RECON"));
    }

    private org.springframework.test.web.servlet.ResultActions sendWebhook(String payload,
                                                                          long timestamp,
                                                                          String signature) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/webhooks/partner")
                .header("X-Timestamp", String.valueOf(timestamp))
                .header("X-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload));
    }

    private void stubPartnerSuccess(String partnerReference) {
        PARTNER.stubFor(post(urlEqualTo(PARTNER_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"partnerReference\":\"" + partnerReference + "\",\"status\":\"SUCCEEDED\"}")));
    }

    private String createBody() {
        return """
                {
                  "customerId": "CUST-1",
                  "amount": 100000.00,
                  "currency": "IDR",
                  "description": "Invoice #1"
                }
                """;
    }
}
