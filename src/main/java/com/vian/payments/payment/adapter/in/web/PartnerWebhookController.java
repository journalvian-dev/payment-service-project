package com.vian.payments.payment.adapter.in.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vian.payments.payment.adapter.in.web.dto.WebhookPayload;
import com.vian.payments.payment.port.in.HandlePartnerWebhookUseCase;
import com.vian.payments.shared.hmac.HmacSigner;
import com.vian.payments.shared.hmac.InvalidSignatureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks")
public class PartnerWebhookController {

    private static final String WEBHOOK_PATH = "/api/v1/webhooks/partner";

    private final HmacSigner hmacSigner;
    private final ObjectMapper objectMapper;
    private final HandlePartnerWebhookUseCase webhookUseCase;

    public PartnerWebhookController(HmacSigner hmacSigner,
                                    ObjectMapper objectMapper,
                                    HandlePartnerWebhookUseCase webhookUseCase) {
        this.hmacSigner = hmacSigner;
        this.objectMapper = objectMapper;
        this.webhookUseCase = webhookUseCase;
    }

    /**
     * Body dibaca sebagai String mentah supaya signature dihitung dari byte
     * yang benar-benar dikirim partner. Kalau di-deserialize dulu lalu
     * di-serialize ulang, signature bisa berubah (key order/whitespace).
     */
    @PostMapping("/partner")
    public ResponseEntity<Void> handlePartnerWebhook(
            @RequestHeader("X-Timestamp") String timestamp,
            @RequestHeader("X-Signature") String signature,
            @RequestBody String rawBody) throws JsonProcessingException {

        long epochSeconds = Long.parseLong(timestamp);

        if (!hmacSigner.verify("POST", WEBHOOK_PATH, epochSeconds, rawBody, signature)) {
            throw new InvalidSignatureException("Signature webhook tidak valid atau timestamp kedaluwarsa");
        }

        WebhookPayload payload = objectMapper.readValue(rawBody, WebhookPayload.class);
        webhookUseCase.handle(payload.toEvent());

        return ResponseEntity.ok().build();
    }
}
