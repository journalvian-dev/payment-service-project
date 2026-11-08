package com.vian.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRouteTest {

    private static final String API_KEY = "test-key";

    private static final WireMockServer DOWNSTREAM =
            new WireMockServer(options().dynamicPort());

    static {
        DOWNSTREAM.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.payment-service-url", DOWNSTREAM::baseUrl);
        registry.add("app.security.api-key", () -> API_KEY);
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.stop();
    }

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        DOWNSTREAM.resetAll();
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void request_dengan_api_key_diteruskan_dan_dapat_request_id() {
        DOWNSTREAM.stubFor(get(urlEqualTo("/api/v1/payments/123"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"123\",\"status\":\"SUCCEEDED\"}")));

        client.get().uri("/api/v1/payments/123")
                .header("X-Api-Key", API_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.id").isEqualTo("123");

        DOWNSTREAM.verify(getRequestedFor(urlEqualTo("/api/v1/payments/123"))
                .withHeader("X-Request-Id", matching(".+")));
    }

    @Test
    void request_tanpa_api_key_ditolak_di_gateway() {
        client.get().uri("/api/v1/payments/123")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().exists("X-Request-Id");

        DOWNSTREAM.verify(0, getRequestedFor(urlEqualTo("/api/v1/payments/123")));
    }

    @Test
    void webhook_tidak_butuh_api_key_karena_pakai_hmac() {
        DOWNSTREAM.stubFor(post(urlEqualTo("/api/v1/webhooks/partner"))
                .willReturn(aResponse().withStatus(200)));

        client.post().uri("/api/v1/webhooks/partner")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();

        DOWNSTREAM.verify(postRequestedFor(urlEqualTo("/api/v1/webhooks/partner")));
    }
}
