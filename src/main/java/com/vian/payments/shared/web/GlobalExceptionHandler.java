package com.vian.payments.shared.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.vian.payments.payment.domain.PaymentNotFoundException;
import com.vian.payments.shared.hmac.InvalidSignatureException;
import com.vian.payments.shared.idempotency.IdempotencyConflictException;
import com.vian.payments.shared.idempotency.RequestInProgressException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(PaymentNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, "Payment tidak ditemukan", e.getMessage(), "PAYMENT_NOT_FOUND");
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyConflict(IdempotencyConflictException e) {
        return respond(HttpStatus.CONFLICT, "Idempotency-Key conflict", e.getMessage(), "IDEMPOTENCY_KEY_REUSED");
    }

    @ExceptionHandler(RequestInProgressException.class)
    public ResponseEntity<ProblemDetail> handleInProgress(RequestInProgressException e) {
        return respond(HttpStatus.CONFLICT, "Request masih diproses", e.getMessage(), "REQUEST_IN_PROGRESS");
    }

    @ExceptionHandler(InvalidSignatureException.class)
    public ResponseEntity<ProblemDetail> handleSignature(InvalidSignatureException e) {
        return respond(HttpStatus.UNAUTHORIZED, "Signature tidak valid", e.getMessage(), "INVALID_SIGNATURE");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validasi gagal",
                "Ada field request yang tidak valid", "VALIDATION_ERROR");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException e) {
        return respond(HttpStatus.BAD_REQUEST, "Header wajib tidak ada",
                "Header " + e.getHeaderName() + " wajib diisi", "MISSING_HEADER");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException e) {
        return respond(HttpStatus.BAD_REQUEST, "Body request tidak valid",
                "JSON tidak dapat dibaca", "INVALID_BODY");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleBadRequest(IllegalArgumentException e) {
        return respond(HttpStatus.BAD_REQUEST, "Request tidak valid", e.getMessage(), "BAD_REQUEST");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "Terjadi kesalahan yang tidak terduga", "INTERNAL_ERROR");
    }

    private ResponseEntity<ProblemDetail> respond(HttpStatus status, String title, String detail, String code) {
        return ResponseEntity.status(status).body(problem(status, title, detail, code));
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
