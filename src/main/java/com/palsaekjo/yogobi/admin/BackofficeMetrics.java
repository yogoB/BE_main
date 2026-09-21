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

    /** 보고서 §9.2 "절감 기회 발견률"의 기준선. 이보다 적은 절감은 옮길 이유가 못 된다고 본 값이다. */
    private static final int OPPORTUNITY_THRESHOLD = 5000;

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
        // 한 번만 조회하고 두 이름으로 낸다. weeklyActivity 는 배포된 화면이 쓰는 옛 키이고,
        // activity 가 퍼널과 시간축이 맞는 14일이다. 화면이 옮겨 가면 옛 키를 지운다.
        var activity = activity();
        out.put("activity", activity);
        out.put("activityWindowDays", activity.size());
        out.put("weeklyActivity", activity.subList(Math.max(0, activity.size() - 7), activity.size()));
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
        // 결손 "대기" 는 아직 손대지 않은 것 + 진행 중인 것이다 — /admin/gaps 기본 목록과 같은 범위여야
        // 뱃지와 목록 건수가 어긋나지 않는다(2026-09-18).
        out.put("gaps", count("SELECT count(*) FROM catalog_candidate WHERE status IN ('REQUESTED', 'IN_PROGRESS')"));
        var funnel = funnel();
        out.put("funnel", funnel);
        out.put("endpoints", endpoints());
        out.put("health", health());
        out.put("quality", quality());
        out.put("stats", stats());
        var savings = savings();
        out.put("savings", savings);
        out.put("kpi", kpi(savings, funnel));
        return out;
    }

    /**
     * 우리 서비스가 찾아 준 절감액(D-54). <b>진단 기준이다</b> — 사용자가 실제로 요금제를 옮겼는지는 모른다.
     * 화면 문구도 "진단에서 확인한 절감액"으로 적는다(D-53 과 같은 선).
     *
     * <p>표본은 <b>로그인한 채 결과 화면을 본 회원</b>이고 계정당 한 행이다(D-59, {@code member_savings}).
     * 한 사람이 여러 번 봐도 한 번 센다 — 아니면 많이 눌러 본 사람이 합계를 끌어올린다.
     * 기준은 "지금 쓰는 요금제 대비"이고, 지금 요금제를 안 알려준 조회는 <b>애초에 행이 안 생긴다</b>(모름 ≠ 0).
     * {@code savedCount} 는 그날 표본이 생기거나 갱신된 사람 수다 — 저장 버튼 수가 아니다.
     *
     * <p>대표값은 <b>중앙값</b>이다. 평균은 한 명의 큰 금액에 끌려가고, 이 표본은 아직 작다.
     * 연 환산은 월 × 12 이며 이름에 {@code Estimate} 를 남긴다 — 1년치 실측이 아니다.
     */
    private Map<String, Object> savings() {
        var out = new LinkedHashMap<String, Object>();
        out.put("basis", "CURRENT_PLAN");
        try {
            Map<String, Object> core = jdbc.queryForMap("""
                    WITH latest AS (
                        SELECT user_id, seen_at, monthly_savings AS amount FROM member_savings
                    )
                    SELECT count(*) AS "members",
                           count(*) FILTER (WHERE amount > 0) AS "improved",
                           count(*) FILTER (WHERE amount >= 5000) AS "opportunity",
                           coalesce(sum(amount) FILTER (WHERE amount > 0), 0) AS "monthlyTotal",
                           coalesce(round(percentile_cont(0.5) WITHIN GROUP (
                               ORDER BY CASE WHEN amount > 0 THEN amount END)), 0) AS "monthlyMedian",
                           coalesce(round(avg(amount) FILTER (WHERE amount > 0)), 0) AS "monthlyAverage",
                           coalesce(max(amount), 0) AS "monthlyMax"
                    FROM latest
                    """);
            out.putAll(core);
            out.put("opportunityThreshold", OPPORTUNITY_THRESHOLD);   // 화면이 기준을 스스로 적지 않게 같이 보낸다
            long monthlyTotal = ((Number) core.get("monthlyTotal")).longValue();
            out.put("annualTotalEstimate", monthlyTotal * 12);   // 월 × 12. 1년치 실측이 아니다
            out.put("histogram", rows("""
                    WITH latest AS (
                        SELECT user_id, seen_at, monthly_savings AS amount FROM member_savings
                    ), bucketed AS (
                        SELECT CASE WHEN amount < 10000 THEN '1만 미만'
                                    WHEN amount < 30000 THEN '1~3만'
                                    WHEN amount < 50000 THEN '3~5만'
                                    WHEN amount < 100000 THEN '5~10만'
                                    ELSE '10만 이상' END AS bucket,
                               CASE WHEN amount < 10000 THEN 1 WHEN amount < 30000 THEN 2
                                    WHEN amount < 50000 THEN 3 WHEN amount < 100000 THEN 4 ELSE 5 END AS ord
                        FROM latest WHERE amount > 0
                    )
                    SELECT bucket, count(*) AS count FROM bucketed GROUP BY bucket, ord ORDER BY ord
                    """));
            out.put("daily", rows("""
                    SELECT to_char(d.day, 'YYYY-MM-DD') AS date,
                           count(s.user_id) AS "savedCount",
                           coalesce(sum(s.monthly_savings) FILTER (WHERE s.monthly_savings > 0), 0) AS "monthlySum"
                      FROM generate_series((CURRENT_DATE - 13)::timestamp, CURRENT_DATE::timestamp, interval '1 day') AS d(day)
                      LEFT JOIN member_savings s ON s.seen_at >= d.day AND s.seen_at < d.day + interval '1 day'
                     GROUP BY d.day ORDER BY d.day
                    """));
        } catch (DataAccessException e) {
            log.warn("절감액 집계 실패: {}", e.getMessage());
            out.put("unavailable", true);
        }
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
        // micrometer 의 Timer.max() 는 최근 2분 창이라 뜸하면 0 이다 — 누적 평균과 나란히 두면 거짓말이 된다.
        // 클라이언트가 켜진 뒤 최대값을 따로 들고 있으므로 그것을 쓴다(2026-09-18).
        out.put("narrationMaxMs", narrator == null ? null : narrator.maxMs());
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
        /*
         * 월 단가가 아닌 금액이 섞였는지 본다. 이 표는 **전부 월 단가**라는 약속 위에 서 있고,
         * 계산기는 고른 등급의 price 를 그대로 월 총액에 더한다. 연간 총액이 한 줄 들어오면
         * 그 등급을 고른 사용자의 "실제 내시는 금액" 이 12배가 되는데 **화면에는 검증할 방법이 없다.**
         * 2026-09-20 에 네 건이 그랬다 — 구글 연간 174,000 · 지니뮤직 4개월/12개월 선불권 ·
         * 교보 북모닝 연간구독 둘.
         *
         * 신호는 둘뿐이다. ① <b>등급 이름</b>의 기간 표기 ② 같은 서비스 중앙값의 10배 초과.
         * 넷을 전부 잡으면서 운영 카탈로그(130건)에 오탐이 <b>0</b> 이다(2026-09-20 실측).
         *
         * <p><b>빼기로 한 신호 둘을 남겨 둔다 — 같은 실수를 반복하지 않도록.</b>
         * <ul>
         *   <li><b>정수배</b>: 스토리지 등급이 서로 정수배인 게 흔하다(iCloud+ 88,000 = 44,000 × 2). 9건.</li>
         *   <li><b>메모의 기간 표기</b>: 메모는 우리가 쓰는 설명문이라 기간 낱말이 늘 섞인다 —
         *       "1개월 정기 구독"(PS Plus 셋) · "멤버 1인당 1개월"(Notion 둘) · "연간 요금제 별도 존재"(Gemini) ·
         *       그리고 <b>선불권을 왜 안 실었는지 적어 둔 문장</b>(지니뮤직)까지 걸렸다. 7건 전부 오탐이다.</li>
         * </ul>
         * 둘 다 "상시 주황" 을 만든다. 카드가 늘 켜져 있으면 진짜 한 건이 섞여도 안 보인다.
         */
        out.put("nonMonthlyTiers", sample("""
                WITH scale AS (
                    SELECT service_id, percentile_cont(0.5) WITHIN GROUP (ORDER BY price) AS mid
                    FROM subscription_tier WHERE active AND currency = 'KRW' AND price > 0 GROUP BY service_id
                )
                SELECT s.name || ' · ' || t.name || ' ' || t.price || '원 (' ||
                       CASE WHEN t.name ~ '(개월|연간|[0-9]년)' THEN '이름의 기간 표기'
                            ELSE '같은 서비스 중앙값의 ' || round(t.price / scale.mid) || '배' END || ')'
                FROM subscription_tier t
                JOIN subscription_service s ON s.id = t.service_id
                JOIN scale ON scale.service_id = t.service_id
                WHERE t.active AND s.active AND t.currency = 'KRW'
                  AND (t.name ~ '(개월|연간|[0-9]년)' OR t.price > scale.mid * 10)
                ORDER BY t.price DESC"""));
        // 999999MB 는 카탈로그의 "무제한" 표기라 더미의 증거가 아니다(126건이 진짜다). 더미는 출처가 진짜 URL 이 아니거나 가격이 0 인 행이다.
        out.put("placeholderPlans", sample("""
                SELECT c.name || ' ' || p.name FROM mobile_plan p JOIN carrier c ON c.id = p.carrier_id
                WHERE p.active AND (p.base_price <= 0 OR p.source_url !~ '^https?://[^/]+\\.[^/]+') ORDER BY p.id DESC"""));
        out.put("plansWithoutSource", sample("""
                SELECT c.name || ' ' || p.name FROM mobile_plan p JOIN carrier c ON c.id = p.carrier_id
                WHERE p.active AND (p.source_url IS NULL OR btrim(p.source_url) = '') ORDER BY p.id"""));
        // 0 건만 보면 "1건뿐" 이 안 보인다 — 사용자에게는 둘이 거의 같다(고를 게 없다). 3건 미만을 얇다고 센다.
        // 통합요금제는 양쪽을 덮는다(D-58).
        out.put("mnoNetworkGaps", sample("""
                SELECT c.name || ' · ' || n.network || ' ' || count(p.id) || '건' FROM carrier c
                CROSS JOIN (VALUES ('FIVE_G'), ('LTE')) AS n(network)
                LEFT JOIN mobile_plan p ON p.carrier_id = c.id AND p.active
                     AND p.network_type IN (n.network, 'LTE_5G')
                WHERE c.carrier_type = 'MNO'
                  AND EXISTS (SELECT 1 FROM mobile_plan x WHERE x.carrier_id = c.id AND x.active)
                GROUP BY c.name, n.network HAVING count(p.id) < 3 ORDER BY count(p.id), c.name"""));
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
            // 창 전체의 **사람 수**. 일별 값을 더하면 사흘 온 사람이 3 이 된다 — 그건 사람-일이지 사람이 아니다.
            // actor_key 로 묶어 한 번만 센다. 같은 CTE 에서 단계 교집합도 나오므로 전환율을 여기서 만든다.
            Map<String, Object> reach = jdbc.queryForMap("""
                    WITH actor AS (
                        SELECT actor_key,
                               bool_or(kind = 'GATE_SHOWN')     AS gate,
                               bool_or(kind = 'MEMBER_LOGIN')   AS login,
                               bool_or(kind = 'REPORT_SHOWN')   AS report,
                               bool_or(kind = 'CALENDAR_SHOWN') AS calendar,
                               bool_or(kind = 'RESULT_SAVED')   AS saved,
                               bool_or(kind = 'INPUT_STARTED')  AS started
                          FROM funnel_event WHERE day > CURRENT_DATE - 14
                         GROUP BY actor_key
                    )
                    SELECT count(*) FILTER (WHERE gate)     AS "gateShown",
                           count(*) FILTER (WHERE login)    AS "memberLogin",
                           count(*) FILTER (WHERE report)   AS "reportShown",
                           count(*) FILTER (WHERE calendar) AS "calendarShown",
                           count(*) FILTER (WHERE saved)    AS "resultSaved",
                           count(*) FILTER (WHERE login AND report)    AS "loginAndReport",
                           count(*) FILTER (WHERE report AND calendar) AS "reportAndCalendar",
                           count(*) FILTER (WHERE report AND saved)    AS "reportAndSaved",
                           count(*) FILTER (WHERE started)                     AS "inputStarted",
                           count(*) FILTER (WHERE started AND (gate OR report)) AS "startedAndReached"
                      FROM actor
                    """);
            var totals = new LinkedHashMap<String, Long>();
            for (String k : List.of("gateShown", "memberLogin", "reportShown", "calendarShown", "resultSaved")) {
                totals.put(k, ((Number) reach.get(k)).longValue());
            }
            var conversion = new LinkedHashMap<String, Object>();
            conversion.put("loginToReport", rate(reach, "loginAndReport", "memberLogin"));
            conversion.put("reportToCalendar", rate(reach, "reportAndCalendar", "reportShown"));
            conversion.put("reportToSaved", rate(reach, "reportAndSaved", "reportShown"));
            // 보고서 §9.2 "결과 도달률". 분모는 입력을 시작한 사람, 분자는 그중 결과 화면까지 간 사람이다.
            // 비회원은 게이트(ip: 키), 회원은 리포트(u: 키)로 도달하고 입력 시작도 같은 키로 찍히므로
            // 두 경로 모두 이어진다 — 익명으로 시작해 중간에 로그인한 사람도 게이트를 먼저 밟아 분자에 든다.
            conversion.put("inputToResult", rate(reach, "startedAndReached", "inputStarted"));
            // 게이트 → 로그인은 **낼 수 없다.** 비회원은 ip: 키, 회원은 u: 키라 같은 사람을 이을 방법이 없다.
            // 둘의 나눗셈은 전환율처럼 보이지만 서로 다른 모집단이다. gateDropEstimate 가 그 한계의 이름이다.
            conversion.put("gateToLogin", null);
            var out = new LinkedHashMap<String, Object>();
            out.put("windowDays", 14);
            // 날짜를 어느 시간대로 잘랐는지. **박아 넣지 않고 세션에서 읽는다** — 설정이 바뀌면
            // 이 값도 따라 바뀌어야지, 화면이 "한국시간 기준"을 적어 둔 채 거짓이 되면 안 된다(G-60).
            out.put("dateBasis", jdbc.queryForObject("SELECT current_setting('TimeZone')", String.class));
            out.put("gateShown", gate);          // 비회원이 결과를 받아 게이트를 만난 횟수 (실측)
            out.put("reportShown", report);      // 회원이 리포트를 본 횟수 (실측)
            out.put("memberLogin", login);       // Google 로그인 성공 (실측)
            out.put("gateDropEstimate", Math.max(0, gate - report));
            out.put("daily", daily);
            out.put("unique", totals);           // 창 전체 사람 수(중복 제거)
            out.put("conversion", conversion);   // 백분율. 모집단이 달라 못 내는 단계는 null 이다
            out.put("inputStarted", ((Number) reach.get("inputStarted")).longValue());   // 결과 도달률의 분모
            out.put("uniqueDaily", unique);
            out.put("lastSeen", lastSeen());     // null 인 종류는 한 번도 안 쌓였다는 뜻이다
            out.put("contaminatedUntil", "2026-09-18");   // 횟수 열은 이 날까지 결과 화면 무한 호출로 부풀어 있다
            return out;
        } catch (org.springframework.dao.DataAccessException e) {
            // 표가 아직 없는 환경(마이그레이션 전)에서도 대시보드 전체가 죽지 않게 한다.
            // **로그는 반드시 남긴다** — 조용히 삼키면 쿼리 버그가 "지표 없음"으로 위장한다.
            log.warn("퍼널 집계를 읽지 못했습니다: {}", e.getMessage());
            return Map.of("windowDays", 14, "unavailable", true);
        }
    }

    /**
     * 빌드에 심어 둔 골든 케이스 수({@code golden-audit.properties}). 세는 곳은 {@code docs/testing.md}
     * 한 곳이고 빌드가 옮겨 적는다 — 개수를 코드에 또 적으면 어긋난다.
     *
     * <p>못 읽으면 <b>{@code null}</b> 이다. 0 을 주면 "지키는 게 하나도 없다"로 읽히는데,
     * 그건 파일을 못 읽었다는 사실과 전혀 다른 말이다.
     */
    private Integer goldenCases() {
        try (var stream = getClass().getResourceAsStream("/golden-audit.properties")) {
            if (stream == null) {
                return null;
            }
            var properties = new java.util.Properties();
            properties.load(stream);
            String cases = properties.getProperty("cases");
            return cases == null ? null : Integer.valueOf(cases);
        } catch (java.io.IOException | NumberFormatException e) {
            log.warn("골든 감사 스탬프를 읽지 못했습니다: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 단계별로 <b>마지막으로 쌓인 날</b>. 한 번도 없으면 {@code null} 이다.
     *
     * <p>집계 실패는 삼켜진다 — 지표 때문에 기능이 멈추면 안 되기 때문이다. 그 대가로 화면에서
     * "아무도 안 했다"와 "못 세고 있다"가 같아 보인다. 실제로 {@code funnel_daily} 의 CHECK 때문에
     * CALENDAR_SHOWN·RESULT_SAVED 가 2026-09-18~21 사흘 동안 통째로 안 쌓였는데 그렇게 보였다(V31).
     * 그래서 표에서 읽지 않고 <b>{@link FunnelCounter#ALL} 을 기준으로 좌외부조인</b>한다 —
     * 행이 아예 없는 종류가 목록에서 사라지지 않고 {@code null} 로 드러난다. 그게 이 값의 전부다.
     */
    private Map<String, Object> lastSeen() {
        var seen = new java.util.HashMap<String, Object>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT kind, to_char(max(day), 'YYYY-MM-DD') AS last FROM funnel_event GROUP BY kind"))
            seen.put((String) row.get("kind"), row.get("last"));

        var out = new LinkedHashMap<String, Object>();
        for (String kind : new java.util.TreeSet<>(com.palsaekjo.yogobi.common.FunnelCounter.ALL))
            out.put(kind, seen.get(kind));
        return out;
    }

    /**
     * 최종보고서 §9.2 의 핵심 KPI 3종. <b>낼 수 있는 것만 값이 있고 나머지는 null 이다</b> —
     * 화면이 셋을 나란히 세우는데, 못 내는 칸을 지우면 "아직 안 만들었나"로 읽히고 0 을 넣으면 거짓이 된다.
     * 각 칸에 {@code *Note} 를 붙여 <b>왜</b> 못 내는지를 여기서 말한다. 한계가 사는 곳이 여기이기 때문이다.
     *
     * <p><b>결과 도달률</b>의 분모는 "입력 시작 사용자"다. 입력은 전부 화면 안에서 일어나 서버에 닿지 않으므로
     * 화면이 {@code POST /api/v1/events} 로 알려 준다(2026-09-21 부터). 분자는 그중 결과 화면까지 간 사람이고,
     * 두 합계의 나눗셈이 아니라 <b>같은 행위자가 두 단계를 다 밟았는지</b>로 센다. 입력이 한 건도 없으면
     * {@code null} 이고 이유를 {@code resultReachRateNote} 로 같이 낸다.
     * <b>계산 오류율</b>은 런타임 값이 아니라 배포 전 골든 감사 결과다 — 대시보드가 낼 숫자가 아니다.
     * <b>절감 기회 발견률</b>만 지금 낼 수 있다: 월 {@value #OPPORTUNITY_THRESHOLD}원 이상 순절감이 가능한
     * 회원 ÷ 유효 계산 회원. 분모는 {@code member_savings} 행이 있는 회원이다 — 지금 요금제를 알려주지 않아
     * 비교가 성립하지 않은 조회는 애초에 행이 없다(모름 ≠ 0).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> kpi(Map<String, Object> savings, Map<String, Object> funnel) {
        var out = new LinkedHashMap<String, Object>();
        out.put("source", "최종보고서 §9.2");

        var conversion = (Map<String, Object>) funnel.get("conversion");
        Object reach = conversion == null ? null : conversion.get("inputToResult");
        out.put("resultReachRate", reach);
        out.put("resultReachOf", funnel.get("inputStarted"));
        if (reach == null)
            out.put("resultReachRateNote", "입력 시작이 아직 한 건도 없다 — 분모가 없다. 화면이 POST /api/v1/events 로 INPUT_STARTED 를 보내면 낸다");

        // 계산 오류율은 **런타임 값이 아니다.** 정답셋과 어긋난 계산이 하나라도 있으면 `check` 가
        // 실패해 배포 자체가 막히므로, 운영에 떠 있는 빌드에서는 정의상 0 이다. 그러니 낼 수 있는 것은
        // "얼마나 틀렸나"가 아니라 **"무엇이 몇 개를 지키고 있나"** 다 — 그 개수를 같이 싣는다.
        out.put("calcErrorRate", 0.0);
        out.put("calcErrorRateBasis", "GOLDEN_GATE");
        out.put("calcErrorRateCases", goldenCases());
        out.put("calcErrorRateNote", "배포 전 골든 감사 기준이다 — 정답셋과 하나라도 어긋나면 배포가 막히므로 "
                + "운영 빌드에서는 언제나 0 이다. 런타임에 측정한 값이 아니다");

        Object members = savings.get("members"), opportunity = savings.get("opportunity");
        if (members instanceof Number below && opportunity instanceof Number above && below.longValue() > 0) {
            out.put("savingOpportunityRate", Math.round(above.longValue() * 1000.0 / below.longValue()) / 10.0);
            out.put("savingOpportunityOf", below.longValue());
        } else {
            out.put("savingOpportunityRate", null);
            out.put("savingOpportunityNote", "유효 계산 회원이 아직 없다");
        }
        out.put("savingOpportunityThreshold", OPPORTUNITY_THRESHOLD);
        return out;
    }

    /**
     * 전환율(%). 모수가 0 이면 <b>0 이 아니라 null</b> 이다 — 아무도 안 온 것과 와서 다 나간 것은 다르다.
     * 소수 한 자리까지만 남긴다. 표본이 두 자리인데 소수점 아래를 늘려 봐야 정밀해지지 않는다.
     */
    private static Double rate(Map<String, Object> row, String numerator, String denominator) {
        long below = ((Number) row.get(denominator)).longValue();
        if (below == 0) return null;
        long above = ((Number) row.get(numerator)).longValue();
        return Math.round(above * 1000.0 / below) / 10.0;
    }

    /**
     * 최근 14일 활동. 가입만으로는 대부분 0 이라 화면이 비어 보인다 —
     * 실제로 움직이는 운영 활동(제보·수집 제안·카탈로그 반영)을 같이 낸다.
     * 표가 없는 환경에서는 빈 목록이고, 화면은 "기록 없음"으로 적는다(0 으로 적지 않는다).
     *
     * <p>창이 <b>퍼널과 같은 14일</b>이다(2026-09-21). 대시보드가 두 그래프를 나란히 놓는데 시간축이
     * 다르면 눈으로 비교할 수 없다. 오래된 {@code weeklyActivity} 키는 이 목록의 뒤 7일이다.
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
                        date_trunc('day', now()) - interval '13 days', date_trunc('day', now()), interval '1 day') AS day
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
