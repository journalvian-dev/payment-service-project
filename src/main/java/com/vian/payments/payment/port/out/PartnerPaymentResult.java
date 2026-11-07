package com.vian.payments.payment.port.out;

/**
 * Hasil pemanggilan partner. UNKNOWN adalah kondisi "in-doubt":
 * kita tidak tahu apakah partner sudah memproses transaksi atau belum.
 * Jangan retry buta; selesaikan lewat query status / reconciliation.
 */
public record PartnerPaymentResult(
        Status status,
        String partnerReference,
        String reason
) {

    public enum Status {
        SUCCEEDED,
        DECLINED,
        UNKNOWN
    }

    public static PartnerPaymentResult approved(String partnerReference) {
        return new PartnerPaymentResult(Status.SUCCEEDED, partnerReference, null);
    }

    public static PartnerPaymentResult declined(String reason) {
        return new PartnerPaymentResult(Status.DECLINED, null, reason);
    }

    public static PartnerPaymentResult unknown(String reason) {
        return new PartnerPaymentResult(Status.UNKNOWN, null, reason);
    }
}
