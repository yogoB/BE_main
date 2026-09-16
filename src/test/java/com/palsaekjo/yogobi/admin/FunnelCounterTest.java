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
}
