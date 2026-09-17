package com.palsaekjo.yogobi.privacy;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * 파기 대상 한 줄. <b>세는 쿼리와 지우는 쿼리가 같은 조건을 쓰게</b> 한 곳에 둔다 —
     * 조건이 두 벌로 갈리면 "미리보기 3건, 실제 삭제 7건"이 되고, 그건 되돌릴 수 없는 사고다.
     */
    private record Target(String name, String table, String where, Object... args) {
    }

    /** 실행 시각에 따라 달라지는 값이 있어 호출마다 만든다. 보유기간은 처리방침과 같은 상수다. */
    private static List<Target> targets() {
        return List.of(
                new Target("payment_record", "payment_record",
                        "paid_at < (now() - (? * interval '1 month'))::date", PrivacyPolicy.PAYMENT_RETENTION_MONTHS),
                // 확인된 증빙은 분석 만료·계정 삭제와 무관하게 자체 기한을 따른다.
                new Target("retained_payment_record", "retained_payment_record",
                        "retain_until <= ?", LocalDate.now(ZoneId.of(PrivacyPolicy.RETENTION_ZONE))),
                new Target("detection_result", "detection_result",
                        "detected_at < now() - (? * interval '1 month')", PrivacyPolicy.DETECTION_RETENTION_MONTHS),
                new Target("auth_session", "auth_session", "expires_at <= now()"),
                new Target("catalog_report", "catalog_report",
                        "created_at < now() - (? * interval '1 day')", PrivacyPolicy.REPORT_RETENTION_DAYS),
                new Target("service_report", "service_report",
                        "created_at < now() - (? * interval '1 day')", PrivacyPolicy.REPORT_RETENTION_DAYS));
    }

    /**
     * 지우지 않고 <b>지금 파기 대상이 몇 건인지만</b> 센다. 운영자가 실행 전에 확인한다.
     * 파기는 되돌릴 수 없으므로 화면은 이 값을 먼저 보여준다.
     */
    public Map<String, Integer> pending() {
        var counts = new LinkedHashMap<String, Integer>();
        for (Target t : targets()) {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM " + t.table() + " WHERE " + t.where(), Integer.class, t.args());
            counts.put(t.name(), n == null ? 0 : n);
        }
        return counts;
    }

    /** 유형별 삭제 건수를 반환한다(운영 점검·테스트용). */
    @Transactional
    public Map<String, Integer> purge() {
        var deleted = new LinkedHashMap<String, Integer>();
        for (Target t : targets()) {
            deleted.put(t.name(), jdbc.update("DELETE FROM " + t.table() + " WHERE " + t.where(), t.args()));
        }
        return deleted;
    }

    @Scheduled(cron = "${yogobi.retention.cron:0 0 4 * * *}", zone = PrivacyPolicy.RETENTION_ZONE)
    public void scheduledPurge() {
        purge();
    }
}
