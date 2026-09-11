package com.palsaekjo.yogobi.privacy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보유기간이 지난 개인 데이터와 만료된 인증 흔적을 파기한다(개인정보 최소 보관).
 * 보유기간은 {@link PrivacyPolicy} 상수 = 처리방침과 같은 값. 기본 매일 04:00 실행.
 */
@Service
public class RetentionService {
    private final JdbcTemplate jdbc;

    public RetentionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 유형별 삭제 건수를 반환한다(운영 점검·테스트용). */
    @Transactional
    public Map<String, Integer> purge() {
        var deleted = new LinkedHashMap<String, Integer>();
        deleted.put("payment_record", jdbc.update(
                "DELETE FROM payment_record WHERE paid_at < (now() - (? * interval '1 month'))::date",
                PrivacyPolicy.PAYMENT_RETENTION_MONTHS));
        deleted.put("detection_result", jdbc.update(
                "DELETE FROM detection_result WHERE detected_at < now() - (? * interval '1 month')",
                PrivacyPolicy.DETECTION_RETENTION_MONTHS));
        deleted.put("auth_email_token", jdbc.update("DELETE FROM auth_email_token WHERE expires_at <= now()"));
        deleted.put("auth_session", jdbc.update("DELETE FROM auth_session WHERE expires_at <= now()"));
        return deleted;
    }

    @Scheduled(cron = "${yogobi.retention.cron:0 0 4 * * *}")
    public void scheduledPurge() {
        purge();
    }
}
