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
