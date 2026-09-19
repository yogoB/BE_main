package com.palsaekjo.yogobi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.palsaekjo.yogobi.common.FunnelCounter;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 백오피스 고도화(D-52, G-38): 결손 처리 흐름 · 제보 메모 · 감사 통합 · 대시보드의 운영 상태/품질/통계 · 퍼널 사람 수.
 */
@SpringBootTest(properties = {
        "JWT_SECRET=VHlwZS1vbmx5LXRlc3Qta2V5LTMyaGFyYWN0ZXJzLW9yLW1vcmUh",
        "AUTH_SECURE_COOKIES=false", "AUTH_SESSION_COOKIE_NAME=YGB_SESSION",
        "ADMIN_ID=yogogo", "ADMIN_PASSWORD=test-backoffice-password",
        "CATALOG_ADMIN_USER_IDS=",
        "yogobi.harvest.cron=0 0 9 1 1 ?"})
@AutoConfigureMockMvc
@Testcontainers
class BackofficeBoardApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FunnelCounter funnel;
    @Autowired com.palsaekjo.yogobi.user.AuthTokens tokens;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE catalog_candidate, admin_action, funnel_event, funnel_daily, service_report");
    }

    /** G-38 a·b — 결손은 요청 많은 순으로 오고, 상태·메모를 바꾸면 기록이 남는다. */
    @Test
    void gapsAreOrderedByDemandAndChangesAreAudited() throws Exception {
        jdbc.update("INSERT INTO catalog_candidate(kind, query_text, status, requested_cnt) VALUES ('MOBILE_PLAN', 'carrier:듣도보도못한모바일', 'REQUESTED', 7)");
        jdbc.update("INSERT INTO catalog_candidate(kind, query_text, status, requested_cnt) VALUES ('SUBSCRIPTION_TIER', 'serviceId:99', 'REQUESTED', 2)");
        jdbc.update("INSERT INTO catalog_candidate(kind, query_text, status, requested_cnt) VALUES ('MOBILE_PLAN', 'carrier:끝난것', 'VERIFIED', 40)");
        Cookie[] admin = loginAsAdmin();

        // 기본 목록은 할 일(REQUESTED·IN_PROGRESS)만, 요청 많은 순.
        String body = send(get("/api/v1/admin/gaps"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].queryText").value("carrier:듣도보도못한모바일"))
                .andExpect(jsonPath("$.data[0].requestedCount").value(7))
                .andReturn().getResponse().getContentAsString();
        long id = JSON.readTree(body).path("data").get(0).path("id").asLong();

        send(post("/api/v1/admin/gaps/{id}", id).with(r -> { r.setMethod("PATCH"); return r; })
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\",\"note\":\"홈페이지 요금표 확인 중\"}"), admin)
                .andExpect(status().isOk());
        assertThat(jdbc.queryForMap("SELECT status, note FROM catalog_candidate WHERE id = ?", id))
                .containsEntry("status", "IN_PROGRESS").containsEntry("note", "홈페이지 요금표 확인 중");
        // 감사 통합 타임라인에 그 행위가 운영자 아이디와 함께 보인다.
        send(get("/api/v1/admin/audit"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("GAP_IN_PROGRESS"))
                .andExpect(jsonPath("$.data[0].target").value("gap:" + id))
                .andExpect(jsonPath("$.data[0].actor").isNotEmpty());
        // 상태 필터.
        send(get("/api/v1/admin/gaps").param("status", "VERIFIED"), admin)
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].queryText").value("carrier:끝난것"));
        send(get("/api/v1/admin/gaps").param("status", "DROP"), admin).andExpect(status().isBadRequest());
    }

    /** G-38 c — 제보에 처리 메모가 붙고, 목록에 메모와 갱신 시각이 보인다. */
    @Test
    void reportNoteIsStoredAndListed() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO service_report(id, category, description, page_url) VALUES (?, 'OTHER', '요금제 검색이 느려요', '/detail')", id);
        Cookie[] admin = loginAsAdmin();
        send(post("/api/v1/admin/reports/{kind}/{id}", "SERVICE", id.toString()).with(r -> { r.setMethod("PATCH"); return r; })
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\",\"note\":\"v66 에서 수정\"}"), admin)
                .andExpect(status().isOk());
        send(get("/api/v1/admin/reports"), admin)
                .andExpect(jsonPath("$.data[0].note").value("v66 에서 수정"))
                .andExpect(jsonPath("$.data[0].updatedAt").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_action WHERE action = 'REPORT_RESOLVED'", Integer.class)).isEqualTo(1);
    }

    /** G-38 d — 대시보드에 운영 상태·품질·통계 블록이 있고, 품질 검사는 실제로 더미를 잡는다. */
    @Test
    void dashboardHasHealthQualityAndStats() throws Exception {
        jdbc.execute("INSERT INTO carrier(id,name,carrier_type) VALUES (9001,'KT','MNO') ON CONFLICT DO NOTHING");
        jdbc.update("""
                INSERT INTO mobile_plan(carrier_id,name,network_type,base_price,data_mb,voice_min,sms_cnt,source_url,collected_at)
                SELECT id,'5G 더미팩','FIVE_G',45000,999999,999999,9999,'http://seed','2026-09-08' FROM carrier WHERE name='KT'""");
        Cookie[] admin = loginAsAdmin();
        send(get("/api/v1/admin/dashboard"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.health.narrationFailures").isNumber())
                .andExpect(jsonPath("$.data.health.recommendationsToday").isNumber())
                // 'http://seed' 는 진짜 출처가 아니다. 999999MB 는 무제한 표기라 그것만으로는 더미가 아니다.
                .andExpect(jsonPath("$.data.quality.placeholderPlans.sample").value(org.hamcrest.Matchers.hasItem("KT 5G 더미팩")))
                .andExpect(jsonPath("$.data.stats.windowDays").value(7))
                .andExpect(jsonPath("$.data.funnel.unique.reportShown").isNumber())
                .andExpect(jsonPath("$.data.funnel.contaminatedUntil").value("2026-09-18"));
        jdbc.update("DELETE FROM mobile_plan WHERE name = '5G 더미팩'");
    }

    /**
     * G-40 — 절감액 트레킹(D-54·D-59). 표본은 로그인한 채 결과를 본 회원이고 계정당 1건,
     * 대표값은 중앙값, 음수·null 은 합계에서 빠진다.
     * 표본: 12,000 / 30,000 / 51,010(같은 계정이 다시 봄) / -3,000 / null.
     */
    @Test
    void savingsTracksOnePerAccountAndUsesMedian() throws Exception {
        jdbc.update("DELETE FROM member_savings");
        jdbc.update("DELETE FROM app_user WHERE email LIKE 'saver%@example.com'");
        saveFor("saver1@example.com", 12000L, "2026-09-17 10:00+09");
        saveFor("saver2@example.com", 30000L, "2026-09-17 11:00+09");
        saveFor("saver3@example.com", 9000L, "2026-09-17 12:00+09");    // 같은 계정이 먼저 본 결과
        saveFor("saver3@example.com", 51010L, "2026-09-18 09:00+09");   // 최신이 이긴다
        saveFor("saver4@example.com", -3000L, "2026-09-18 09:30+09");   // 지금이 더 싼 사람
        saveFor("saver5@example.com", null, "2026-09-18 09:40+09");     // 현재 요금제를 모르는 조회

        send(get("/api/v1/admin/dashboard"), loginAsAdmin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.savings.basis").value("CURRENT_PLAN"))
                .andExpect(jsonPath("$.data.savings.members").value(4))        // 현재 요금제를 모르면 표본이 아니다
                .andExpect(jsonPath("$.data.savings.improved").value(3))       // 음수 제외
                .andExpect(jsonPath("$.data.savings.monthlyTotal").value(93010))       // 12,000+30,000+51,010
                .andExpect(jsonPath("$.data.savings.monthlyMedian").value(30000))
                .andExpect(jsonPath("$.data.savings.monthlyMax").value(51010))
                .andExpect(jsonPath("$.data.savings.annualTotalEstimate").value(1116120))
                .andExpect(jsonPath("$.data.savings.histogram.length()").value(3))
                .andExpect(jsonPath("$.data.savings.daily.length()").value(14));
    }

    /** G-44 — 회원 운영(D-57 ⑥): 검색·세션 회수. 운영자는 회원을 지우지 않는다 — 탈퇴는 본인만 한다. */
    @Test
    void memberBoardSearchesAndRevokesSessions() throws Exception {
        jdbc.update("DELETE FROM app_user WHERE email LIKE 'ops%@example.com'");
        long id = com.palsaekjo.yogobi.user.TestMembers.create(jdbc, "ops1@example.com");
        com.palsaekjo.yogobi.user.TestMembers.session(tokens, id);   // 살아 있는 세션 하나
        Cookie[] admin = loginAsAdmin();

        send(get("/api/v1/admin/members").param("q", "ops1"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].email").value("ops1@example.com"))
                .andExpect(jsonPath("$.data[0].activeSessions").value(1))
                .andExpect(jsonPath("$.data[0].savedResults").value(0));

        send(post("/api/v1/admin/members/{id}/sessions", id).with(r -> { r.setMethod("DELETE"); return r; }), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revoked").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_session WHERE user_id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_action WHERE action = 'MEMBER_SESSIONS_REVOKED'",
                Integer.class)).isEqualTo(1);
        // 없는 회원은 404. 그리고 회원을 지우는 경로는 아예 없다.
        send(post("/api/v1/admin/members/{id}/sessions", 999999).with(r -> { r.setMethod("DELETE"); return r; }), admin)
                .andExpect(status().isNotFound());
    }

    private void saveFor(String email, Long amount, String savedAt) {
        Long userId = jdbc.query("SELECT id FROM app_user WHERE email = ?", (rs, i) -> rs.getLong(1), email)
                .stream().findFirst().orElse(null);
        if (userId == null) userId = com.palsaekjo.yogobi.user.TestMembers.create(jdbc, email);
        if (amount == null) return;   // 지금 요금제를 모르면 행 자체가 없다
        jdbc.update("""
                INSERT INTO member_savings(user_id, monthly_savings, seen_at) VALUES (?, ?, ?::timestamptz)
                ON CONFLICT (user_id) DO UPDATE SET monthly_savings = EXCLUDED.monthly_savings, seen_at = EXCLUDED.seen_at
                """, userId, amount, savedAt);
    }

    /** G-38 e — 같은 사람이 하루에 열 번 봐도 사람 수는 1, 횟수는 10. 무한 호출 사고가 다시 나도 사람 수는 안 부푼다. */
    @Test
    void funnelCountsPeopleOncePerDay() {
        for (int i = 0; i < 10; i++) funnel.record(FunnelCounter.REPORT_SHOWN, "u:42");
        funnel.record(FunnelCounter.REPORT_SHOWN, "ip:203.0.113.9");
        assertThat(jdbc.queryForObject("SELECT count FROM funnel_daily WHERE day = CURRENT_DATE AND kind = 'REPORT_SHOWN'", Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM funnel_event WHERE day = CURRENT_DATE AND kind = 'REPORT_SHOWN'", Integer.class)).isEqualTo(2);
    }

    private Cookie[] loginAsAdmin() throws Exception {
        return send(post("/api/v1/admin/login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsBytes(Map.of("id", "yogogo", "password", "test-backoffice-password"))),
                new Cookie[0]).andExpect(status().isOk()).andReturn().getResponse().getCookies();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Cookie[] cookies) throws Exception {
        var session = new MockHttpSession();
        var issued = mvc.perform(withCookies(get("/api/v1/auth/csrf").session(session), cookies))
                .andExpect(status().isOk()).andReturn();
        String token = JSON.readTree(issued.getResponse().getContentAsString()).path("data").path("token").asText();
        return mvc.perform(withCookies(request.session(session), cookies).header("X-CSRF-TOKEN", token));
    }

    private static MockHttpServletRequestBuilder withCookies(MockHttpServletRequestBuilder request, Cookie[] cookies) {
        return cookies.length == 0 ? request : request.cookie(cookies);
    }
}
