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

    @BeforeEach void clear() {
        jdbc.update("DELETE FROM funnel_daily");
        jdbc.update("DELETE FROM funnel_event");   // 사람 수·전환율이 앞 테스트의 행위자를 물려받지 않게 한다
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

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> part(String key) {
        var funnel = (java.util.Map<String, Object>) metrics.dashboard().get("funnel");
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
}
