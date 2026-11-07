package com.vian.payments.sandbox;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vian.payments.payment.adapter.out.partner.dto.PartnerChargeRequest;
import com.vian.payments.payment.adapter.out.partner.dto.PartnerChargeResponse;
import com.vian.payments.shared.hmac.HmacSigner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Partner palsu untuk demo lokal (menggantikan Sunline/ESB/PSP).
 *
 * Skenario berdasarkan 2 digit terakhir amount:
 * - x.77 -> lambat 3 detik  => client timeout => IN_DOUBT, lalu reconciliation
 * - x.99 -> HTTP 500         => IN_DOUBT (partner mungkin sudah proses)
 * - x.55 -> HTTP 402 decline => FAILED
 * - lainnya -> SUCCEEDED
 */
@RestController
@RequestMapping("/sandbox/partner")
@ConditionalOnProperty(prefix = "app.sandbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SandboxPartnerController {

    private static final String CHARGE_PATH = "/sandbox/partner/payments";
    private static final BigDecimal SLOW = new BigDecimal("0.77");
    private static final BigDecimal ERROR = new BigDecimal("0.99");
    private static final BigDecimal DECLINE = new BigDecimal("0.55");

    private final Map<String, PartnerChargeResponse> statuses = new ConcurrentHashMap<>();
    private final HmacSigner hmacSigner;
    private final ObjectMapper objectMapper;

    public SandboxPartnerController(HmacSigner hmacSigner, ObjectMapper objectMapper) {
        this.hmacSigner = hmacSigner;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/payments")
    public ResponseEntity<?> charge(
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody String rawBody) throws JsonProcessingException, InterruptedException {

        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "X-Timestamp tidak valid"));
        }

        if (!hmacSigner.verify("POST", CHARGE_PATH, epochSeconds, rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Signature tidak valid"));
        }

        PartnerChargeRequest request = objectMapper.readValue(rawBody, PartnerChargeRequest.class);
        BigDecimal fraction = request.amount()
                .remainder(BigDecimal.ONE)
                .setScale(2, RoundingMode.HALF_UP);

        if (fraction.compareTo(SLOW) == 0) {
            Thread.sleep(3_000);
            return ResponseEntity.ok(succeed(request.reference()));
        }
        if (fraction.compareTo(ERROR) == 0) {
            statuses.put(request.reference(), new PartnerChargeResponse(null, "PROCESSING", "PARTNER_ERROR"));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "partner internal error"));
        }
        if (fraction.compareTo(DECLINE) == 0) {
            PartnerChargeResponse declined = new PartnerChargeResponse(null, "DECLINED", "INSUFFICIENT_FUNDS");
            statuses.put(request.reference(), declined);
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(declined);
        }
        return ResponseEntity.ok(succeed(request.reference()));
    }

    @GetMapping("/payments/{reference}")
    public PartnerChargeResponse status(@PathVariable String reference) {
        return statuses.getOrDefault(reference,
                new PartnerChargeResponse(null, "PROCESSING", "BELUM_DIPROSES"));
    }

    private PartnerChargeResponse succeed(String reference) {
        PartnerChargeResponse response = new PartnerChargeResponse(
                "PTR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "SUCCEEDED",
                null
        );
        statuses.put(reference, response);
        return response;
    }
}
