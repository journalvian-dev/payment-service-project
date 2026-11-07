package com.vian.payments.shared.hmac;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.vian.payments.config.AppProperties;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 untuk request keluar dan webhook masuk.
 *
 * Canonical string: METHOD \n PATH \n TIMESTAMP \n SHA256_HEX(BODY)
 * - timestamp mencegah replay (dicek dengan toleransi 5 menit)
 * - body di-hash supaya canonical string tidak rusak oleh newline
 * - perbandingan signature pakai MessageDigest.isEqual (constant time)
 */
@Component
public class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final long MAX_SKEW_SECONDS = 300;

    private final byte[] secret;
    private final Clock clock;

    public HmacSigner(AppProperties properties, Clock clock) {
        this.secret = properties.partner().secret().getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public String sign(String method, String path, long timestamp, String body) {
        String canonical = method.toUpperCase() + "\n"
                + path + "\n"
                + timestamp + "\n"
                + sha256Hex(body == null ? "" : body);
        return hmacHex(canonical);
    }

    /**
     * @param provided header signature, boleh dengan prefix "v1="
     */
    public boolean verify(String method, String path, long timestamp, String body, String provided) {
        if (provided == null || provided.isBlank()) {
            return false;
        }
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - timestamp) > MAX_SKEW_SECONDS) {
            return false;
        }

        String normalized = provided.startsWith("v1=") ? provided.substring(3) : provided;
        String expected = sign(method, path, timestamp, body);

        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                normalized.getBytes(StandardCharsets.UTF_8)
        );
    }

    public String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 tidak tersedia", e);
        }
    }

    private String hmacHex(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Gagal membuat HMAC", e);
        }
    }
}
