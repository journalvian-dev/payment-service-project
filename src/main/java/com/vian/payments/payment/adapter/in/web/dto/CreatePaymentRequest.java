package com.vian.payments.payment.adapter.in.web.dto;

import java.math.BigDecimal;

import com.vian.payments.payment.application.CreatePaymentCommand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreatePaymentRequest(
        @NotBlank(message = "customerId wajib diisi")
        @Size(max = 64, message = "customerId maksimal 64 karakter")
        String customerId,

        @NotNull(message = "amount wajib diisi")
        @DecimalMin(value = "0.01", message = "amount minimal 0.01")
        @Digits(integer = 17, fraction = 2, message = "amount maksimal 2 angka desimal")
        BigDecimal amount,

        @NotBlank(message = "currency wajib diisi")
        @Pattern(regexp = "[A-Za-z]{3}", message = "currency harus 3 huruf, contoh IDR")
        String currency,

        @Size(max = 255, message = "description maksimal 255 karakter")
        String description
) {

    public CreatePaymentCommand toCommand() {
        return new CreatePaymentCommand(customerId, amount, currency, description);
    }
}
