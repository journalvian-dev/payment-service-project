package com.vian.payments.payment.adapter.out.partner;

import com.vian.payments.payment.adapter.out.partner.dto.PartnerChargeRequest;
import com.vian.payments.payment.adapter.out.partner.dto.PartnerChargeResponse;
import com.vian.payments.payment.port.out.PartnerChargeCommand;
import com.vian.payments.payment.port.out.PartnerClient;
import com.vian.payments.payment.port.out.PartnerPaymentResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Adapter yang menerjemahkan response/error partner menjadi domain result.
 *
 * Mapping error -> UNKNOWN adalah inti in-doubt handling:
 * - timeout / connection reset / 5xx  => UNKNOWN (bisa jadi sudah diproses)
 * - 4xx business decline              => DECLINED (pasti tidak diproses)
 */
@Service
public class PartnerWebClientAdapter implements PartnerClient {

    private static final Logger log = LoggerFactory.getLogger(PartnerWebClientAdapter.class);

    private final PartnerHttpClient httpClient;

    public PartnerWebClientAdapter(PartnerHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    @CircuitBreaker(name = "partner", fallbackMethod = "chargeFallback")
    public PartnerPaymentResult charge(PartnerChargeCommand command) {
        try {
            PartnerChargeResponse response = httpClient.charge(new PartnerChargeRequest(
                    command.reference(),
                    command.amount(),
                    command.currency(),
                    command.customerId()
            ));
            return map(response);
        } catch (WebClientResponseException e) {
            int code = e.getStatusCode().value();
            if (e.getStatusCode().is4xxClientError() && code != 401 && code != 403 && code != 408 && code != 429) {
                return PartnerPaymentResult.declined("HTTP " + code + ": " + e.getResponseBodyAsString());
            }
            throw e;
        }
    }

    @Override
    @CircuitBreaker(name = "partner", fallbackMethod = "statusFallback")
    public PartnerPaymentResult queryStatus(String reference) {
        return map(httpClient.queryStatus(reference));
    }

    private PartnerPaymentResult map(PartnerChargeResponse response) {
        if (response == null || response.status() == null) {
            return PartnerPaymentResult.unknown("Response partner kosong");
        }
        return switch (response.status().toUpperCase()) {
            case "SUCCEEDED" -> PartnerPaymentResult.approved(response.partnerReference());
            case "DECLINED" -> PartnerPaymentResult.declined(response.reason());
            default -> PartnerPaymentResult.unknown("Status partner: " + response.status());
        };
    }

    private PartnerPaymentResult chargeFallback(PartnerChargeCommand command, Throwable throwable) {
        log.warn("Circuit breaker/retry gagal untuk charge {}: {}", command.reference(), throwable.toString());
        return PartnerPaymentResult.unknown("Partner tidak dapat dihubungi: " + throwable.getClass().getSimpleName());
    }

    private PartnerPaymentResult statusFallback(String reference, Throwable throwable) {
        log.warn("Circuit breaker/retry gagal untuk query status {}: {}", reference, throwable.toString());
        return PartnerPaymentResult.unknown("Partner tidak dapat dihubungi");
    }
}
