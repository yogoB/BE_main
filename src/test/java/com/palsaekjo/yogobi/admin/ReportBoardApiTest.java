package com.palsaekjo.yogobi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.palsaekjo.yogobi.user.AuthTokens;
import com.palsaekjo.yogobi.user.TestMembers;
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
 * 제보 게시판(운영자 전용). 지표는 두 표를 합쳐 세고 있었지만 내용을 볼 화면이 없었다.
 *
 * <p>여기서 지키는 것은 셋이다 — ① 두 출처가 한 목록에 최신순으로 온다
 * ② 운영자가 아니면 못 본다 ③ <b>제보자 회원 신원이 응답에 섞이지 않는다</b>.
 */
@SpringBootTest(properties = {
        "JWT_SECRET=VHlwZS1vbmx5LXRlc3Qta2V5LTMyaGFyYWN0ZXJzLW9yLW1vcmUh",
        "AUTH_SECURE_COOKIES=false", "AUTH_SESSION_COOKIE_NAME=YGB_SESSION",
        "ADMIN_ID=yogogo", "ADMIN_PASSWORD=test-backoffice-password",
        "CATALOG_ADMIN_USER_IDS=",
        "yogobi.harvest.cron=0 0 9 1 1 ?"})
@AutoConfigureMockMvc
@Testcontainers
class ReportBoardApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthTokens tokens;

    private UUID serviceReportId;

    @BeforeEach
    void fixtures() {
        jdbc.execute("DELETE FROM service_report");
        jdbc.execute("DELETE FROM catalog_report");
        // 관리자 계정도 app_user 행이다(D-32). 통째로 지우면 로그인이 깨지므로 이 테스트가 만든 회원만 지운다.
        jdbc.update("DELETE FROM app_user WHERE email IN (?, ?)", "reporter@example.com", "member@example.com");
        long memberId = TestMembers.create(jdbc, "reporter@example.com");
        serviceReportId = UUID.randomUUID();
        // 로그인 상태로 낸 제보다 — user_id 가 붙어 있고, 그 값이 응답에 새면 안 된다(D-42).
        jdbc.update("""
                INSERT INTO service_report(id, category, description, page_url, user_id, created_at)
                VALUES (?, 'SYSTEM', '결과 화면에서 다음 버튼이 눌리지 않아요', '/results', ?, now())
                """, serviceReportId, memberId);
        long planId = jdbc.queryForObject("SELECT id FROM mobile_plan WHERE active ORDER BY id LIMIT 1", Long.class);
        jdbc.update("""
                INSERT INTO catalog_report(id, target_type, target_id, field, description, created_at)
                VALUES (?, 'MOBILE_PLAN', ?, 'PRICE', '월정액이 실제와 달라요', now() - interval '1 hour')
                """, UUID.randomUUID(), planId);
    }

    @Test
    void adminSeesBothSourcesNewestFirstWithoutAnyMemberIdentity() throws Exception {
        String body = send(get("/api/v1/admin/reports"), loginAsAdmin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                // 화면 제보가 1시간 더 최근이라 먼저 온다.
                .andExpect(jsonPath("$.data[0].kind").value("SERVICE"))
                .andExpect(jsonPath("$.data[0].pageUrl").value("/results"))
                .andExpect(jsonPath("$.data[0].detail").value("SYSTEM"))
                .andExpect(jsonPath("$.data[1].kind").value("CATALOG"))
                .andExpect(jsonPath("$.data[1].detail").value("PRICE"))
                // 상품 이름이 채워져야 운영자가 무엇에 대한 제보인지 알 수 있다.
                .andExpect(jsonPath("$.data[1].target").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        long memberId = jdbc.queryForObject("SELECT user_id FROM service_report WHERE id = ?", Long.class, serviceReportId);
        assertThat(body).doesNotContain("userId").doesNotContain("user_id")
                .doesNotContain("reporter@example.com")
                .doesNotContain("\"" + memberId + "\"");
    }

    @Test
    void statusFilterAndUpdateWork() throws Exception {
        Cookie[] admin = loginAsAdmin();
        send(post("/api/v1/admin/reports/{kind}/{id}", "SERVICE", serviceReportId.toString())
                .with(r -> { r.setMethod("PATCH"); return r; })
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"), admin)
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT status FROM service_report WHERE id = ?", String.class, serviceReportId))
                .isEqualTo("RESOLVED");
        send(get("/api/v1/admin/reports").param("status", "PENDING"), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].kind").value("CATALOG"));
    }

    @Test
    void nobodyButAnOperatorCanReadOrChangeReports() throws Exception {
        mvc.perform(get("/api/v1/admin/reports")).andExpect(status().isUnauthorized());
        Cookie[] member = TestMembers.login(jdbc, tokens, "member@example.com");
        send(get("/api/v1/admin/reports"), member).andExpect(status().isForbidden());
        send(post("/api/v1/admin/reports/{kind}/{id}", "SERVICE", serviceReportId.toString())
                .with(r -> { r.setMethod("PATCH"); return r; })
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"), member)
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT status FROM service_report WHERE id = ?", String.class, serviceReportId))
                .isEqualTo("PENDING");
    }

    @Test
    void badInputsAreRejected() throws Exception {
        Cookie[] admin = loginAsAdmin();
        send(get("/api/v1/admin/reports").param("status", "DROP"), admin).andExpect(status().isBadRequest());
        send(post("/api/v1/admin/reports/{kind}/{id}", "MEMBERS", serviceReportId.toString())
                .with(r -> { r.setMethod("PATCH"); return r; })
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"), admin)
                .andExpect(status().isBadRequest());
        send(post("/api/v1/admin/reports/{kind}/{id}", "SERVICE", UUID.randomUUID().toString())
                .with(r -> { r.setMethod("PATCH"); return r; })
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"), admin)
                .andExpect(status().isNotFound());
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
