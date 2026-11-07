package com.vian.payments.payment.adapter.in.web;

import java.util.UUID;

import com.vian.payments.payment.adapter.in.web.dto.CreatePaymentRequest;
import com.vian.payments.payment.application.PaymentResult;
import com.vian.payments.payment.port.in.CreatePaymentUseCase;
import com.vian.payments.payment.port.in.GetPaymentUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final CreatePaymentUseCase createPaymentUseCase;
    private final GetPaymentUseCase getPaymentUseCase;

    public PaymentController(CreatePaymentUseCase createPaymentUseCase,
                             GetPaymentUseCase getPaymentUseCase) {
        this.createPaymentUseCase = createPaymentUseCase;
        this.getPaymentUseCase = getPaymentUseCase;
    }

    /**
     * Idempotency-Key wajib. Request kedua dengan key sama:
     * - body sama -> response pertama dikembalikan (200 + header Idempotent-Replayed: true)
     * - body beda -> 409
     * - masih diproses -> 409
     */
    @PostMapping
    public ResponseEntity<PaymentResult> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {

        PaymentResult result = createPaymentUseCase.create(idempotencyKey, request.toCommand());

        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .header("Idempotent-Replayed", Boolean.toString(result.replayed()))
                .body(result);
    }

    @GetMapping("/{id}")
    public PaymentResult getById(@PathVariable UUID id) {
        return getPaymentUseCase.getById(id);
    }
}
