package com.palsaekjo.yogobi.admin;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 백오피스 대시보드 지표(D-32). **DB에 기록이 있는 것만 낸다** — 없는 숫자를 만들지 않는다.
 *
 * <p>Fly 관리형 그라파나(D-25)는 그대로 두고, 여기서는 운영자가 한 화면에서 볼 값만 모은다.
 * 화면 단위 이탈율·퍼널은 브라우저 이벤트를 수집하지 않아 낼 수 없다(D-25에 기록된 한계 그대로).
 * 요청수·지연은 micrometer 레지스트리에서 읽으므로 **프로세스 재시작 시 0부터** 다시 센다.
 */
@Component
public class BackofficeMetrics {
    /** 화면에 보여줄 엔드포인트 수. 꼬리까지 나열하면 읽히지 않는다. */
    private static final int TOP_ENDPOINTS = 8;

    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;

    public BackofficeMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    public String loginId(long userId) {
        return jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, userId);
    }

    public Map<String, Object> dashboard() {
        var out = new LinkedHashMap<String, Object>();
        out.put("members", Map.of(
                "total", count("SELECT count(*) FROM app_user"),
                "signedUp24h", count("SELECT count(*) FROM app_user WHERE created_at > now() - interval '24 hours'"),
                "signedUp7d", count("SELECT count(*) FROM app_user WHERE created_at > now() - interval '7 days'"),
                "withSubscription", count("SELECT count(DISTINCT user_id) FROM user_subscription WHERE ended_at IS NULL"),
                "activeSessions", count("SELECT count(*) FROM auth_session WHERE expires_at > now()")));
        out.put("signupTrend", trend());
        out.put("catalog", Map.of(
                "mobilePlans", count("SELECT count(*) FROM mobile_plan WHERE active"),
                "subscriptionServices", count("SELECT count(*) FROM subscription_service WHERE active"),
                "subscriptionTiers", count("SELECT count(*) FROM subscription_tier WHERE active"),
                "planBenefits", count("SELECT count(*) FROM plan_benefit")));
        out.put("review", Map.of(
                "pendingRequests", count("SELECT count(*) FROM catalog_change_request WHERE status = 'PENDING'"),
                "mismatched", count("""
                        SELECT count(*) FROM catalog_change_request
                        WHERE status = 'PENDING' AND review_status = 'MISMATCH'"""),
                "unverified", count("""
                        SELECT count(*) FROM catalog_change_request
                        WHERE status = 'PENDING' AND review_status = 'UNVERIFIED'"""),
                "appliedToday", count("""
                        SELECT count(*) FROM catalog_audit
                        WHERE outcome = 'APPLIED' AND created_at > now() - interval '24 hours'""")));
        out.put("reports", Map.of(
                "pending", count("SELECT count(*) FROM catalog_report WHERE status = 'PENDING'"),
                "total", count("SELECT count(*) FROM catalog_report")));
        out.put("gaps", count("SELECT count(*) FROM catalog_candidate WHERE status = 'REQUESTED'"));
        out.put("endpoints", endpoints());
        return out;
    }

    /** 최근 7일 일별 가입 수. 가입이 없는 날도 0으로 채워 그래프가 끊기지 않게 한다. */
    private List<Map<String, Object>> trend() {
        try {
            return jdbc.queryForList("""
                    SELECT to_char(day, 'YYYY-MM-DD') AS date,
                           (SELECT count(*) FROM app_user u
                            WHERE u.created_at >= day AND u.created_at < day + interval '1 day') AS count
                    FROM generate_series(
                        date_trunc('day', now()) - interval '6 days', date_trunc('day', now()), interval '1 day') AS day
                    ORDER BY day""");
        } catch (DataAccessException e) {
            return List.of();
        }
    }

    /** 요청수 상위 엔드포인트와 평균 지연(ms). micrometer 가 자동 수집한 값을 그대로 읽는다. */
    private List<Map<String, Object>> endpoints() {
        return registry.find("http_server_requests").timers().stream()
                .filter(timer -> timer.count() > 0)
                .sorted(Comparator.comparingLong(Timer::count).reversed())
                .limit(TOP_ENDPOINTS)
                .map(timer -> Map.<String, Object>of(
                        "uri", String.valueOf(timer.getId().getTag("uri")),
                        "method", String.valueOf(timer.getId().getTag("method")),
                        "status", String.valueOf(timer.getId().getTag("status")),
                        "count", timer.count(),
                        "avgMs", Math.round(timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS) * 10) / 10.0))
                .toList();
    }

    /** 표가 아직 없거나 DB 가 흔들려도 대시보드 전체가 죽지 않게 한다 — 그 칸만 -1(알 수 없음)로 둔다. */
    private long count(String sql) {
        try {
            Long value = jdbc.queryForObject(sql, Long.class);
            return value == null ? -1 : value;
        } catch (DataAccessException e) {
            return -1;
        }
    }
}
