package com.palsaekjo.yogobi.privacy;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit server-side classification only. No member API can choose statutory retention. */
@Service
public class PaymentRetentionService {
    private final JdbcTemplate jdbc;

    public PaymentRetentionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Preserve confirmed evidence in the transaction that creates/classifies it, before any deletion.
     * A responsible policy owner supplies the basis/start/deadline; tax periods need not start at payment.
     * retainUntil is exclusive: erase on that date, after the last required retention date.
     */
    @Transactional
    public void preserve(long paymentId, String legalBasis, LocalDate retentionStart, LocalDate retainUntil) {
        if (legalBasis == null || legalBasis.isBlank() || legalBasis.length() > 500 || retentionStart == null
                || retainUntil == null || !retainUntil.isAfter(retentionStart)
                || !retainUntil.isAfter(LocalDate.now(java.time.ZoneId.of(PrivacyPolicy.RETENTION_ZONE))))
            throw new IllegalArgumentException("Confirmed legal basis and valid retention dates are required");
        // Lock source evidence until the separate copy commits, including when deletion/purge races this operation.
        int inserted = jdbc.update("""
                INSERT INTO retained_payment_record
                    (payment_record_id,merchant_raw,service_id,amount,paid_at,source,legal_basis,retention_start,retain_until)
                SELECT id,merchant_raw,service_id,amount,paid_at,source,?,?,?
                FROM payment_record WHERE id=? FOR SHARE
                ON CONFLICT (payment_record_id) DO NOTHING
                """, legalBasis.strip(), retentionStart, retainUntil, paymentId);
        if (inserted != 1) throw new IllegalArgumentException("Payment missing or evidence already retained; existing evidence was not changed");
    }
}
