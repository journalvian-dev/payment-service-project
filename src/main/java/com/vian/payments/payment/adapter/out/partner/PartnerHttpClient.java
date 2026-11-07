package com.vian.payments.payment.adapter.out.partner;

import java.time.Clock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vian.payments.config.AppProperties;
import com.vian.payments.payment.adapter.out.partner.dto.PartnerChargeRequest;
import com.vian.payments.payment.adapter.out.partner.dto.PartnerChargeResponse;
import com.vian.payments.shared.hmac.HmacSigner;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Lapisan HTTP murni. Retry ada di sini supaya circuit breaker di adapter
 * melihat hasil akhir (setelah retry), bukan setiap percobaan.
 */
@Component
public class PartnerHttpClient {

    private static final String CHARGE_PATH = "/sandbox/partner/payments";

    private final WebClient webClient;
    private final HmacSigner hmacSigner;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PartnerHttpClient(WebClient partnerWebClient,
                             HmacSigner hmacSigner,
                             AppProperties properties,
                             ObjectMapper objectMapper,
                             Clock clock) {
        this.webClient = partnerWebClient;
        this.hmacSigner = hmacSigner;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Retry aman karena reference kita dikirim sebagai Idempotency-Key ke partner.
     * Tanpa idempotency key di sisi partner, retry POST charge = risiko double charge.
     */
    @Retry(name = "partner")
    public PartnerChargeResponse charge(PartnerChargeRequest request) {
        String body = writeJson(request);
        long timestamp = clock.instant().getEpochSecond();
        String signature = hmacSigner.sign("POST", CHARGE_PATH, timestamp, body);

        return webClient.post()
                .uri(CHARGE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Client-Id", properties.partner().clientId())
                .header("X-Timestamp", String.valueOf(timestamp))
                .header("X-Signature", "v1=" + signature)
                .header("Idempotency-Key", request.reference())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(PartnerChargeResponse.class)
                .block();
    }

    @Retry(name = "partner")
    public PartnerChargeResponse queryStatus(String reference) {
        return webClient.get()
                .uri(CHARGE_PATH + "/{reference}", reference)
                .header("X-Client-Id", properties.partner().clientId())
                .retrieve()
                .bodyToMono(PartnerChargeResponse.class)
                .block();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Gagal serialisasi request partner", e);
        }
    }
}
