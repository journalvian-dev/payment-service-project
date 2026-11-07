package com.vian.payments.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Value object uang. Skala dipaksa 2 dan currency ISO-4217 (3 huruf).
 */
public record Money(BigDecimal amount, String currency) {

    public Money {
        if (amount == null) {
            throw new IllegalArgumentException("amount wajib diisi");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount harus lebih besar dari 0");
        }
        if (currency == null || !currency.matches("[A-Za-z]{3}")) {
            throw new IllegalArgumentException("currency harus 3 huruf (contoh: IDR)");
        } else {
            currency = currency.toUpperCase(Locale.ROOT);
        }
        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("amount maksimal 2 angka di belakang koma", e);
        }
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }
}
