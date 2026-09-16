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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BackofficeMetrics.class);

    /** 화면에 보여줄 엔드포인트 수. 꼬리까지 나열하면 읽히지 않는다. */
    private static final int TOP_ENDPOINTS = 8;

    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final com.palsaekjo.yogobi.catalog.CombinedCatalogStore store;

    public BackofficeMetrics(JdbcTemplate jdbc, MeterRegistry registry,
                             com.palsaekjo.yogobi.catalog.CombinedCatalogStore store) {
        this.store = store;
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
        out.put("weeklyActivity", activity());
        out.put("catalog", Map.of(
                "sourceDiverged", store.diverged(),   // 외부 원본이 레포 CSV 를 덮고 있는가(D-24 단일 원본 위반)
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
        out.put("funnel", funnel());
        out.put("endpoints", endpoints());
        return out;
    }

    /**
     * D-36 로그인 게이트 퍼널. "이탈율이 높으면 비회원에게 연다"는 기준은 숫자가 있어야 작동한다.
     *
     * <p>게이트·리포트·로그인 셋은 **실측**이다. 이탈만 뺄셈(게이트 − 리포트)이라 `추정` 으로 이름 붙였다 —
     * 화면에서 실측과 구분되지 않으면 나중에 판단 근거가 흐려진다.
     *
     * <p>`funnel_daily` 는 재기동에도 남는다. micrometer 는 인메모리라 Fly 머신이 auto_stop 으로
     * 내려가면 리셋된다 — 며칠짜리 CBT 판단에는 쓸 수 없다.
     */
    private Map<String, Object> funnel() {
        try {
            var daily = jdbc.queryForList("""
                    SELECT to_char(d.day, 'YYYY-MM-DD') AS date,
                           coalesce(sum(f.count) FILTER (WHERE f.kind = 'GATE_SHOWN'), 0)   AS "gateShown",
                           coalesce(sum(f.count) FILTER (WHERE f.kind = 'REPORT_SHOWN'), 0) AS "reportShown",
                           coalesce(sum(f.count) FILTER (WHERE f.kind = 'MEMBER_LOGIN'), 0) AS "memberLogin"
                      FROM generate_series((CURRENT_DATE - 13)::timestamp, CURRENT_DATE::timestamp,
                                           interval '1 day') AS d(day)
                      LEFT JOIN funnel_daily f ON f.day = d.day::date
                     GROUP BY d.day ORDER BY d.day DESC
                    """);
            long gate = 0, report = 0, login = 0;
            for (Map<String, Object> row : daily) {
                gate += ((Number) row.get("gateShown")).longValue();
                report += ((Number) row.get("reportShown")).longValue();
                login += ((Number) row.get("memberLogin")).longValue();
            }
            return Map.of(
                    "windowDays", 14,
                    "gateShown", gate,          // 비회원이 결과를 받아 게이트를 만난 횟수 (실측)
                    "reportShown", report,      // 회원이 리포트를 본 횟수 (실측)
                    "memberLogin", login,       // Google 로그인 성공 (실측)
                    "gateDropEstimate", Math.max(0, gate - report),
                    "daily", daily);
        } catch (org.springframework.dao.DataAccessException e) {
            // 표가 아직 없는 환경(마이그레이션 전)에서도 대시보드 전체가 죽지 않게 한다.
            // **로그는 반드시 남긴다** — 조용히 삼키면 쿼리 버그가 "지표 없음"으로 위장한다.
            log.warn("퍼널 집계를 읽지 못했습니다: {}", e.getMessage());
            return Map.of("windowDays", 14, "unavailable", true);
        }
    }

    /** 최근 7일 일별 가입 수. 가입이 없는 날도 0으로 채워 그래프가 끊기지 않게 한다. */
    /**
     * 최근 7일 활동. 가입만으로는 대부분 0 이라 화면이 비어 보인다 —
     * 실제로 움직이는 운영 활동(제보·수집 제안·카탈로그 반영)을 같이 낸다.
     * 표가 없는 환경에서는 빈 목록이고, 화면은 "기록 없음"으로 적는다(0 으로 적지 않는다).
     */
    private List<Map<String, Object>> activity() {
        try {
            return jdbc.queryForList("""
                    SELECT to_char(day, 'YYYY-MM-DD') AS date,
                           (SELECT count(*) FROM app_user u
                             WHERE u.created_at >= day AND u.created_at < day + interval '1 day') AS signups,
                           (SELECT count(*) FROM catalog_report r
                             WHERE r.created_at >= day AND r.created_at < day + interval '1 day') AS reports,
                           (SELECT count(*) FROM catalog_change_request c
                             WHERE c.created_at >= day AND c.created_at < day + interval '1 day') AS proposals,
                           (SELECT count(*) FROM catalog_audit a
                             WHERE a.created_at >= day AND a.created_at < day + interval '1 day'
                               AND a.outcome = 'APPLIED') AS applied
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
