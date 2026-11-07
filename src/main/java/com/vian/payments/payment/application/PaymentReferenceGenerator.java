package com.vian.payments.payment.application;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

@Component
public class PaymentReferenceGenerator {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final Clock clock;

    public PaymentReferenceGenerator(Clock clock) {
        this.clock = clock;
    }

    public String next() {
        int random = ThreadLocalRandom.current().nextInt(0x1000000);
        return "PAY-" + TIMESTAMP.format(clock.instant()) + "-" + String.format("%06X", random);
    }
}
