package com.palsaekjo.yogobi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.palsaekjo.yogobi.common.FunnelCounter;
import com.palsaekjo.yogobi.user.AuthTokens;
import com.palsaekjo.yogobi.user.TestMembers;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * docs/testing.md G-23 — D-36 로그인 게이트 퍼널 집계.
 *
 * <p>"이탈율이 높으면 비회원에게 연다"는 기준은 숫자가 있어야 작동한다. 그 숫자가 실제로 쌓이는지,
 * 그리고 <b>집계가 죽어도 추천은 계속되는지</b>를 고정한다 — 지표 때문에 기능이 멈추면 안 된다.
 */
@SpringBootTest(properties = {"JWT_SECRET=VHlwZS1vbmx5LXRlc3Qta2V5LTMyaGFyYWN0ZXJzLW9yLW1vcmUh",
        "AUTH_SECURE_COOKIES=false", "AUTH_SESSION_COOKIE_NAME=YGB_SESSION"})
@AutoConfigureMockMvc
@Testcontainers
class FunnelCounterTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    private static final String REQUEST = """
            {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},"optional":{"contractType":"NONE"}}""";

    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthTokens tokens;
    @Autowired BackofficeMetrics metrics;
    @Autowired FunnelCounter counter;
    @Autowired com.palsaekjo.yogobi.common.FunnelBackfill backfill;

    @BeforeEach void clear() {
        jdbc.update("DELETE FROM funnel_daily");
        jdbc.update("DELETE FROM funnel_event");   // 사람 수·전환율이 앞 테스트의 행위자를 물려받지 않게 한다
        jdbc.update("DELETE FROM member_savings");
        jdbc.update("DELETE FROM saved_result");
        jdbc.execute("TRUNCATE app_user, auth_rate_limit CASCADE");   // 같은 이메일을 여러 테스트가 쓴다
    }

    private long counted(String kind) {
        return jdbc.queryForList("SELECT count FROM funnel_daily WHERE kind = ?", Long.class, kind)
                .stream().findFirst().orElse(0L);
    }

    @Test void g23a_guestResultCountsAsGateShown() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk());

        assertThat(counted(FunnelCounter.GATE_SHOWN)).isEqualTo(1);
        assertThat(counted(FunnelCounter.REPORT_SHOWN)).isZero();
    }

    @Test void g23b_memberResultCountsAsReportShown() throws Exception {
        Cookie[] member = TestMembers.login(jdbc, tokens, "member@example.com");
        mvc.perform(post("/api/v1/recommendations").cookie(member)
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk());

        assertThat(counted(FunnelCounter.REPORT_SHOWN)).isEqualTo(1);
        assertThat(counted(FunnelCounter.GATE_SHOWN)).isZero();
    }

    @Test void g23c_sameDayAccumulatesInOneRowInsteadOfGrowing() throws Exception {
        for (int i = 0; i < 3; i++)
            mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                    .andExpect(status().isOk());

        assertThat(counted(FunnelCounter.GATE_SHOWN)).isEqualTo(3);
        // 이벤트마다 행을 쌓지 않는다 — 날짜×종류로 합친다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM funnel_daily", Integer.class)).isEqualTo(1);
    }

    /** 지표가 죽어도 사용자는 결과를 받아야 한다. 표를 지워 집계를 강제로 실패시킨다. */
    @Test void g23d_countingFailureDoesNotBreakTheRecommendation() throws Exception {
        jdbc.execute("ALTER TABLE funnel_daily RENAME TO funnel_daily_hidden");
        try {
            mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                    .andExpect(status().isOk());
        } finally {
            jdbc.execute("ALTER TABLE funnel_daily_hidden RENAME TO funnel_daily");
        }
    }

    @Test void g23e_dashboardSeparatesMeasuredCountsFromTheDerivedDrop() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk());
        Cookie[] member = TestMembers.login(jdbc, tokens, "member@example.com");
        mvc.perform(post("/api/v1/recommendations").cookie(member)
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        var funnel = (java.util.Map<String, Object>) metrics.dashboard().get("funnel");
        assertThat(funnel.get("gateShown")).isEqualTo(2L);       // 실측
        assertThat(funnel.get("reportShown")).isEqualTo(1L);     // 실측
        assertThat(funnel.get("gateDropEstimate")).isEqualTo(2L - 1L);   // 뺄셈이라 이름에 Estimate 가 붙는다
    }

    /** funnel_event 에 날짜·종류·행위자를 직접 넣는다. 며칠에 걸친 표본을 MockMvc 로는 만들 수 없다. */
    private void seen(int daysAgo, String kind, String actor) {
        jdbc.update("INSERT INTO funnel_event (day, kind, actor_key) VALUES (CURRENT_DATE - ?, ?, ?)"
                + " ON CONFLICT DO NOTHING", daysAgo, kind, actor);
    }

    /** {@code unique}·{@code conversion} 은 funnel 안, {@code kpi} 는 최상위다. */
    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> part(String key) {
        var dashboard = metrics.dashboard();
        if (dashboard.containsKey(key)) return (java.util.Map<String, Object>) dashboard.get(key);
        var funnel = (java.util.Map<String, Object>) dashboard.get("funnel");
        return (java.util.Map<String, Object>) funnel.get(key);
    }

    /** G-56 a — 사흘 온 한 사람은 1 이다. 일별 값을 더하면 3 이 되는데 그건 사람-일이지 사람이 아니다. */
    @Test void g56a_uniqueCountsPeopleNotPersonDays() {
        seen(0, FunnelCounter.REPORT_SHOWN, "u:1");
        seen(1, FunnelCounter.REPORT_SHOWN, "u:1");
        seen(2, FunnelCounter.REPORT_SHOWN, "u:1");

        assertThat(part("unique").get("reportShown")).isEqualTo(1L);
    }

    /** G-56 b — 전환율은 같은 행위자가 두 단계를 다 밟았는지로 낸다. 두 합계의 나눗셈이 아니다. */
    @Test void g56b_conversionCountsActorsPresentInBothStages() {
        seen(0, FunnelCounter.MEMBER_LOGIN, "u:1");
        seen(0, FunnelCounter.REPORT_SHOWN, "u:1");
        seen(3, FunnelCounter.RESULT_SAVED, "u:1");   // 같은 사람, 다른 날 — 창 안이면 이어진다
        seen(0, FunnelCounter.MEMBER_LOGIN, "u:2");   // 로그인만 하고 리포트는 안 봤다

        var conversion = part("conversion");
        assertThat(conversion.get("loginToReport")).isEqualTo(50.0);    // 로그인 2명 중 1명
        assertThat(conversion.get("reportToSaved")).isEqualTo(100.0);   // 리포트 1명 중 1명
    }

    /** G-56 c — 못 내는 비율은 0 이 아니라 null 이다. 0%는 "다 이탈했다"는 말이라 거짓이 된다. */
    @Test void g56c_unmeasurableRatesAreNullNotZero() {
        seen(0, FunnelCounter.GATE_SHOWN, "ip:203.0.113.9");
        seen(0, FunnelCounter.MEMBER_LOGIN, "u:1");

        var conversion = part("conversion");
        // 아무도 리포트를 안 봤다 → 모수 0
        assertThat(conversion.get("reportToCalendar")).isNull();
        // 비회원은 ip: 키, 회원은 u: 키다. 모집단이 달라 이을 수 없다 — 나눠서 100% 를 만들지 않는다.
        assertThat(conversion).containsKey("gateToLogin");
        assertThat(conversion.get("gateToLogin")).isNull();
    }

    private void sendEvent(String body) throws Exception {
        mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
    }

    private long events(String kind) {
        return jdbc.queryForObject("SELECT count(*) FROM funnel_event WHERE kind = ?", Long.class, kind);
    }

    /** G-57 a — 화면만 아는 단계는 받는다. 보고서 §9.2 "결과 도달률"의 분모다. */
    @Test void g57a_clientReportedStageIsRecorded() throws Exception {
        sendEvent("""
                {"kind":"INPUT_STARTED"}""");

        assertThat(events("INPUT_STARTED")).isEqualTo(1);
    }

    /**
     * G-57 b — <b>서버가 스스로 보는 단계는 받지 않는다.</b> 받으면 요청 한 번으로 리포트 조회 수를
     * 부풀릴 수 있고, 그러면 퍼널이 증거로서 죽는다. 거절도 하지 않는다 — 조용히 버린다.
     */
    @Test void g57b_serverObservedStagesAreNotAcceptedFromTheClient() throws Exception {
        for (String forged : new String[] {FunnelCounter.REPORT_SHOWN, FunnelCounter.MEMBER_LOGIN,
                FunnelCounter.GATE_SHOWN, FunnelCounter.CALENDAR_SHOWN, FunnelCounter.RESULT_SAVED})
            sendEvent("{\"kind\":\"" + forged + "\"}");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM funnel_event", Long.class)).isZero();
    }

    /** G-57 c — 모르는 종류·빈 본문도 204 다. 지표 때문에 화면 콘솔이 시끄러워지면 안 된다. */
    @Test void g57c_unknownKindIsDroppedWithoutAnError() throws Exception {
        sendEvent("""
                {"kind":"NOT_A_STAGE"}""");
        sendEvent("{}");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM funnel_event", Long.class)).isZero();
    }

    /** G-57 d — 절감 기회 발견률은 월 5,000원 이상 회원 ÷ 유효 계산 회원이다(보고서 §9.2). */
    @Test void g57d_savingOpportunityRateUsesTheReportThreshold() {
        long a = TestMembers.create(jdbc, "a@example.com");
        long b = TestMembers.create(jdbc, "b@example.com");
        long c = TestMembers.create(jdbc, "c@example.com");
        jdbc.update("INSERT INTO member_savings (user_id, monthly_savings) VALUES (?, 9000)", a);
        jdbc.update("INSERT INTO member_savings (user_id, monthly_savings) VALUES (?, 5000)", b);   // 경계는 포함
        jdbc.update("INSERT INTO member_savings (user_id, monthly_savings) VALUES (?, 4999)", c);   // 경계 바로 아래

        var kpi = part("kpi");
        assertThat(kpi.get("savingOpportunityRate")).isEqualTo(66.7);   // 3명 중 2명
        assertThat(kpi.get("savingOpportunityOf")).isEqualTo(3L);
        assertThat(kpi.get("savingOpportunityThreshold")).isEqualTo(5000);
    }

    /** G-57 e — 아직 못 내는 KPI 는 키를 지우지도, 0 을 넣지도 않는다. 왜 못 내는지를 같이 낸다. */
    @Test void g57e_unavailableKpisCarryTheirReason() {
        var kpi = part("kpi");
        assertThat(kpi).containsKey("resultReachRate");
        assertThat(kpi.get("resultReachRate")).isNull();
        assertThat((String) kpi.get("resultReachRateNote")).contains("입력 시작");
        assertThat(kpi.get("calcErrorRate")).isNull();
    }

    /**
     * G-57 f — <b>부를 수 있는 모든 단계가 두 표에 실제로 들어가는지 쓸어본다.</b>
     *
     * <p>이 테스트가 없어서 CALENDAR_SHOWN·RESULT_SAVED 가 2026-09-18 부터 한 건도 안 쌓였다.
     * {@code funnel_daily.kind} 의 CHECK 가 D-36 당시의 세 종류만 허용했는데, 집계 실패는 삼켜지도록
     * 되어 있어(기능이 지표 때문에 멈추면 안 된다) WARN 한 줄만 남고 화면에서는 "아직 아무도 안 했다"와
     * 구분되지 않았다. 한 종류만 고치고 끝내지 않도록 <b>상수를 반사로 훑는다</b> — 새 단계를 추가하면
     * 목록을 고치지 않아도 여기서 자동으로 걸린다.
     */
    @Test void g57f_everyKindActuallyLandsInBothTables() throws Exception {
        var declared = new java.util.TreeSet<String>();
        for (var f : FunnelCounter.class.getDeclaredFields())
            if (f.getType() == String.class && java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    && java.lang.reflect.Modifier.isPublic(f.getModifiers()))
                declared.add((String) f.get(null));
        assertThat(declared).hasSizeGreaterThanOrEqualTo(5);   // 상수를 지웠는데 통과하는 일은 없게 한다
        // 대시보드가 "한 번도 안 쌓인 종류"를 찾을 때 쓰는 집합이다. 여기서 빠지면 그 종류는
        // 표에 행이 없어도 화면에서 사라져 버린다 — 이번 사고가 정확히 그 모양이었다.
        assertThat(FunnelCounter.ALL).containsAll(declared).containsAll(FunnelCounter.CLIENT_REPORTED);

        for (String kind : new java.util.TreeSet<>(FunnelCounter.ALL)) {
            counter.record(kind, "u:1");
            assertThat(counted(kind)).as("%s 가 funnel_daily 에 없다", kind).isEqualTo(1);
            assertThat(events(kind)).as("%s 가 funnel_event 에 없다", kind).isEqualTo(1);
        }
    }

    /**
     * G-57 g — <b>한 번도 안 쌓인 종류가 목록에서 사라지지 않는다.</b> 표를 group by 로만 읽으면
     * 행이 없는 종류는 결과에 아예 안 나오고, 화면에서 "아무도 안 했다"와 구분되지 않는다.
     * 이번 사고(CALENDAR_SHOWN·RESULT_SAVED 가 사흘간 통째로 누락)를 눈으로 잡는 장치다.
     */
    @Test void g57g_lastSeenListsEveryKindIncludingTheOnesNeverRecorded() {
        counter.record(FunnelCounter.GATE_SHOWN, "ip:203.0.113.9");

        var lastSeen = part("lastSeen");
        assertThat(lastSeen.keySet()).isEqualTo(new java.util.TreeSet<>(FunnelCounter.ALL));
        assertThat(lastSeen.get(FunnelCounter.GATE_SHOWN)).isNotNull();
        assertThat(lastSeen.get(FunnelCounter.RESULT_SAVED)).isNull();   // 없으면 null — 키가 사라지지 않는다
    }

    /** {@code counted()} 는 종류당 한 행만 본다 — 복원은 여러 날에 걸치므로 날짜를 집어서 읽는다. */
    private long savedOn(int daysAgo) {
        return jdbc.queryForList("SELECT count FROM funnel_daily WHERE kind = 'RESULT_SAVED' AND day = CURRENT_DATE - ?",
                Long.class, daysAgo).stream().findFirst().orElse(0L);
    }

    private void saved(long userId, int daysAgo) {
        jdbc.update("""
                INSERT INTO saved_result (user_id, request, cost, saved_at)
                VALUES (?, '{}'::jsonb, '{}'::jsonb, now() - make_interval(days => ?))""", userId, daysAgo);
    }

    /**
     * G-58 — 놓친 {@code RESULT_SAVED} 를 {@code saved_result} 에서 되살린다. 저장은 행이 남으므로
     * 추정이 아니라 복원이다. 사람은 하루에 한 번만 세고, 여러 번 돌려도 결과가 같아야 한다.
     */
    @Test void g58a_missedSavesAreRestoredFromTheRowsThatRemain() {
        long a = TestMembers.create(jdbc, "a@example.com");
        long b = TestMembers.create(jdbc, "b@example.com");
        saved(a, 3);
        saved(a, 3);   // 같은 사람이 같은 날 두 번 저장 — 사람 수로는 1 이다
        saved(b, 3);
        saved(a, 2);

        backfill.backfill();

        assertThat(events(FunnelCounter.RESULT_SAVED)).isEqualTo(3);   // (a,3일전) (b,3일전) (a,2일전)
        assertThat(savedOn(3)).isEqualTo(3);

        assertThat(savedOn(2)).isEqualTo(1);

        backfill.backfill();   // 두 번 돌려도 같아야 한다
        assertThat(events(FunnelCounter.RESULT_SAVED)).isEqualTo(3);
        assertThat(savedOn(3)).isEqualTo(3);
        assertThat(savedOn(2)).isEqualTo(1);
    }

    /**
     * G-58 b — <b>오늘치는 {@code funnel_daily} 에 넣지 않는다.</b> 오늘은 배포된 순간부터 실시간
     * 집계가 주인이라, 복원이 거기에 더하면 두 번 세게 된다. 사람 수({@code funnel_event})는
     * 기본키가 막아 주므로 오늘까지 넣는다.
     */
    @Test void g58b_todayIsLeftToLiveCountingInTheCountTable() {
        long a = TestMembers.create(jdbc, "a@example.com");
        saved(a, 0);

        backfill.backfill();

        assertThat(events(FunnelCounter.RESULT_SAVED)).isEqualTo(1);   // 사람 수는 들어간다
        assertThat(counted(FunnelCounter.RESULT_SAVED)).isZero();      // 횟수는 실시간 집계에 맡긴다
    }

    /**
     * G-58 c — <b>근거가 없는 단계는 만들지 않는다.</b> 변경 시점 판정은 읽기 응답일 뿐 아무 행도
     * 남기지 않아 되살릴 수 없다. 다른 값으로 대신 세우면 그 순간 퍼널이 증거이기를 그만둔다.
     */
    @Test void g58c_stagesWithoutEvidenceAreNotInvented() {
        long a = TestMembers.create(jdbc, "a@example.com");
        saved(a, 2);

        backfill.backfill();

        assertThat(events(FunnelCounter.CALENDAR_SHOWN)).isZero();
        assertThat(events("INPUT_STARTED")).isZero();
    }

    /**
     * G-59 — 보고서 §9.2 "결과 도달률". 분모는 입력을 시작한 사람, 분자는 그중 결과 화면까지 간 사람이다.
     * 비회원은 게이트(ip: 키), 회원은 리포트(u: 키)로 도달하고 <b>두 경로 모두 이어져야 한다</b> —
     * 한쪽만 세면 회원이 많은 날과 비회원이 많은 날의 값이 서로 다른 뜻이 된다.
     */
    @Test void g59a_resultReachRateJoinsBothTheGuestAndMemberPaths() {
        seen(0, "INPUT_STARTED", "ip:203.0.113.9");
        seen(0, FunnelCounter.GATE_SHOWN, "ip:203.0.113.9");   // 비회원: 입력 → 게이트에서 결과를 봤다
        seen(0, "INPUT_STARTED", "u:1");
        seen(1, FunnelCounter.REPORT_SHOWN, "u:1");            // 회원: 다른 날이어도 창 안이면 이어진다
        seen(0, "INPUT_STARTED", "ip:203.0.113.10");           // 입력만 하고 떠났다

        var kpi = part("kpi");
        assertThat(kpi.get("resultReachRate")).isEqualTo(66.7);   // 3명 중 2명
        assertThat(kpi.get("resultReachOf")).isEqualTo(3L);
        assertThat(kpi).doesNotContainKey("resultReachRateNote");  // 값이 있으면 이유를 달지 않는다
    }

    /** G-59 b — 입력 시작이 한 건도 없으면 0%가 아니라 null 이고, 왜 없는지를 같이 낸다. */
    @Test void g59b_withoutAnyInputTheRateIsNullWithItsReason() {
        seen(0, FunnelCounter.GATE_SHOWN, "ip:203.0.113.9");

        var kpi = part("kpi");
        assertThat(kpi.get("resultReachRate")).isNull();
        assertThat(kpi.get("resultReachOf")).isEqualTo(0L);
        assertThat((String) kpi.get("resultReachRateNote")).contains("입력 시작");
    }
}
