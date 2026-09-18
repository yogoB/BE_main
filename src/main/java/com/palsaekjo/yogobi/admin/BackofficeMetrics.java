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
    private final com.palsaekjo.yogobi.recommend.NarratorClient narrator;

    public BackofficeMetrics(JdbcTemplate jdbc, MeterRegistry registry,
                             com.palsaekjo.yogobi.catalog.CombinedCatalogStore store,
                             org.springframework.beans.factory.ObjectProvider<com.palsaekjo.yogobi.recommend.NarratorClient> narrator) {
        this.store = store;
        this.jdbc = jdbc;
        this.registry = registry;
        this.narrator = narrator.getIfAvailable();
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
        // 상품 제보(catalog_report) + 화면·기타 제보(service_report, D-41)를 합쳐 센다.
        out.put("reports", Map.of(
                "pending", count("""
                        SELECT (SELECT count(*) FROM catalog_report WHERE status = 'PENDING')
                             + (SELECT count(*) FROM service_report WHERE status = 'PENDING')"""),
                "total", count("""
                        SELECT (SELECT count(*) FROM catalog_report) + (SELECT count(*) FROM service_report)""")));
        out.put("gaps", count("SELECT count(*) FROM catalog_candidate WHERE status = 'REQUESTED'"));
        out.put("funnel", funnel());
        out.put("endpoints", endpoints());
        out.put("health", health());
        out.put("quality", quality());
        out.put("stats", stats());
        return out;
    }

    /**
     * 운영 상태(D-52 ①). 어제 설명 경로 사고 넷과 결과 화면 무한 호출은 화면에선 전부 정상으로 보였다 —
     * 이 타일이 있었으면 첫 요청에서 드러났다. 카운터·타이머는 프로세스 수명이라 "켜진 뒤" 값이고,
     * 추천 횟수는 DB(`recommendation_daily`)라 남는다.
     */
    private Map<String, Object> health() {
        var failures = new LinkedHashMap<String, Object>();
        double total = 0;
        for (var counter : registry.find("narration.unavailable").counters()) {
            failures.put(String.valueOf(counter.getId().getTag("kind")), Math.round(counter.count()));
            total += counter.count();
        }
        Timer latency = registry.find("narration.latency").timer();
        var out = new LinkedHashMap<String, Object>();
        out.put("narrationFailures", Math.round(total));
        out.put("narrationFailuresByKind", failures);
        out.put("narrationCalls", latency == null ? 0 : latency.count());
        out.put("narrationAvgMs", latency == null ? null : Math.round(latency.mean(java.util.concurrent.TimeUnit.MILLISECONDS)));
        out.put("narrationMaxMs", latency == null ? null : Math.round(latency.max(java.util.concurrent.TimeUnit.MILLISECONDS)));
        out.put("narratorLastOkAt", narrator == null ? null : narrator.lastOk());
        out.put("recommendationsToday", count("SELECT coalesce(sum(count), 0) FROM recommendation_daily WHERE day = CURRENT_DATE"));
        // 사람 수 대비 횟수. 1 에 가까우면 정상, 크게 벌어지면 결과 화면이 반복 호출하고 있다는 뜻이다(9/17 사고의 모양).
        out.put("reportShownToday", count("SELECT coalesce(sum(count), 0) FROM funnel_daily WHERE day = CURRENT_DATE AND kind = 'REPORT_SHOWN'"));
        out.put("reportViewersToday", count("SELECT count(*) FROM funnel_event WHERE day = CURRENT_DATE AND kind = 'REPORT_SHOWN'"));
        return out;
    }

    /**
     * 데이터 품질(D-52 ②). 유령 요금제 22행이 추천 1위였는데 아무도 몰랐다. SQL 여섯 개가 매일 본다.
     * 건수와 표본(최대 5개)만 — 고치는 것은 카탈로그 CRUD 가 한다.
     */
    private Map<String, Object> quality() {
        var out = new LinkedHashMap<String, Object>();
        out.put("carrierNameVariants", sample("""
                SELECT string_agg(c.name, ' / ') FROM carrier c
                WHERE EXISTS (SELECT 1 FROM mobile_plan p WHERE p.carrier_id = c.id AND p.active)
                GROUP BY lower(replace(c.name, ' ', '')) HAVING count(*) > 1"""));
        out.put("duplicateTierNames", sample("""
                SELECT s.name || ' · ' || min(t.name) || ' ×' || count(*) FROM subscription_tier t
                JOIN subscription_service s ON s.id = t.service_id
                WHERE t.active AND s.active GROUP BY s.name, lower(replace(t.name, ' ', '')) HAVING count(*) > 1"""));
        out.put("samePriceTiers", sample("""
                SELECT s.name || ' · ' || t.price || '원 ×' || count(*) || ' (' || string_agg(t.name, ', ') || ')'
                FROM subscription_tier t JOIN subscription_service s ON s.id = t.service_id
                WHERE t.active AND s.active AND t.currency = 'KRW' GROUP BY s.name, t.price HAVING count(*) > 1"""));
        // 999999MB 는 카탈로그의 "무제한" 표기라 더미의 증거가 아니다(126건이 진짜다). 더미는 출처가 진짜 URL 이 아니거나 가격이 0 인 행이다.
        out.put("placeholderPlans", sample("""
                SELECT c.name || ' ' || p.name FROM mobile_plan p JOIN carrier c ON c.id = p.carrier_id
                WHERE p.active AND (p.base_price <= 0 OR p.source_url !~ '^https?://[^/]+\\.[^/]+') ORDER BY p.id DESC"""));
        out.put("plansWithoutSource", sample("""
                SELECT c.name || ' ' || p.name FROM mobile_plan p JOIN carrier c ON c.id = p.carrier_id
                WHERE p.active AND (p.source_url IS NULL OR btrim(p.source_url) = '') ORDER BY p.id"""));
        out.put("mnoNetworkGaps", sample("""
                SELECT c.name || ' · ' || n.network || ' 0건' FROM carrier c
                CROSS JOIN (VALUES ('FIVE_G'), ('LTE')) AS n(network)
                WHERE c.carrier_type = 'MNO' AND EXISTS (SELECT 1 FROM mobile_plan p WHERE p.carrier_id = c.id AND p.active)
                  AND NOT EXISTS (SELECT 1 FROM mobile_plan p WHERE p.carrier_id = c.id AND p.active AND p.network_type = n.network)"""));
        return out;
    }

    /**
     * 추천·저장 통계(D-52 ⑦). 최근 7일 1순위 요금제 상위, 데이터 구간 분포, 많이 저장된 요금제.
     * 수집 우선순위와 제품 판단의 근거다. 개인 단위 값은 없다.
     */
    private Map<String, Object> stats() {
        var out = new LinkedHashMap<String, Object>();
        out.put("windowDays", 7);
        out.put("topRecommended", rows("""
                SELECT c.name AS carrier, p.name AS plan, sum(r.count) AS count
                FROM recommendation_daily r JOIN mobile_plan p ON p.id = r.plan_id JOIN carrier c ON c.id = p.carrier_id
                WHERE r.day > CURRENT_DATE - 7 GROUP BY c.name, p.name ORDER BY count DESC LIMIT 10"""));
        out.put("dataGbHistogram", rows("""
                SELECT data_gb AS "dataGb", sum(count) AS count FROM recommendation_daily
                WHERE day > CURRENT_DATE - 7 GROUP BY data_gb ORDER BY data_gb"""));
        out.put("topSaved", rows("""
                SELECT s.cost->>'carrier' AS carrier, s.cost->>'planName' AS plan, count(*) AS count
                FROM saved_result s GROUP BY 1, 2 ORDER BY count DESC LIMIT 10"""));
        out.put("savedTotal", count("SELECT count(*) FROM saved_result"));
        return out;
    }

    private Map<String, Object> sample(String sql) {
        try {
            List<String> all = jdbc.queryForList(sql, String.class);
            return Map.of("count", all.size(), "sample", all.stream().limit(5).toList());
        } catch (DataAccessException e) {
            log.warn("품질 검사 쿼리 실패: {}", e.getMessage());
            return Map.of("count", -1, "sample", List.of());
        }
    }

    private List<Map<String, Object>> rows(String sql) {
        try {
            return jdbc.queryForList(sql);
        } catch (DataAccessException e) {
            log.warn("통계 쿼리 실패: {}", e.getMessage());
            return List.of();
        }
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
            // 사람 수(D-52 ③): 같은 행위자는 하루 단계당 한 번. 결과 화면 무한 호출(9/17~18) 로 횟수는 오염됐지만
            // 이 값은 그 뒤(funnel_event 도입 후)부터 쌓이므로 깨끗하다.
            var unique = jdbc.queryForList("""
                    SELECT to_char(d.day, 'YYYY-MM-DD') AS date,
                           count(*) FILTER (WHERE f.kind = 'GATE_SHOWN')     AS "gateShown",
                           count(*) FILTER (WHERE f.kind = 'MEMBER_LOGIN')   AS "memberLogin",
                           count(*) FILTER (WHERE f.kind = 'REPORT_SHOWN')   AS "reportShown",
                           count(*) FILTER (WHERE f.kind = 'CALENDAR_SHOWN') AS "calendarShown",
                           count(*) FILTER (WHERE f.kind = 'RESULT_SAVED')   AS "resultSaved"
                      FROM generate_series((CURRENT_DATE - 13)::timestamp, CURRENT_DATE::timestamp, interval '1 day') AS d(day)
                      LEFT JOIN funnel_event f ON f.day = d.day::date
                     GROUP BY d.day ORDER BY d.day DESC
                    """);
            var totals = new LinkedHashMap<String, Long>();
            for (String k : List.of("gateShown", "memberLogin", "reportShown", "calendarShown", "resultSaved")) {
                long sum = 0;
                for (Map<String, Object> row : unique) sum += ((Number) row.get(k)).longValue();
                totals.put(k, sum);
            }
            var out = new LinkedHashMap<String, Object>();
            out.put("windowDays", 14);
            out.put("gateShown", gate);          // 비회원이 결과를 받아 게이트를 만난 횟수 (실측)
            out.put("reportShown", report);      // 회원이 리포트를 본 횟수 (실측)
            out.put("memberLogin", login);       // Google 로그인 성공 (실측)
            out.put("gateDropEstimate", Math.max(0, gate - report));
            out.put("daily", daily);
            out.put("unique", totals);           // 사람 수 — 단계별 전환율은 이걸로 낸다
            out.put("uniqueDaily", unique);
            out.put("contaminatedUntil", "2026-09-18");   // 횟수 열은 이 날까지 결과 화면 무한 호출로 부풀어 있다
            return out;
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
                             WHERE r.created_at >= day AND r.created_at < day + interval '1 day')
                           + (SELECT count(*) FROM service_report s
                             WHERE s.created_at >= day AND s.created_at < day + interval '1 day') AS reports,
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
