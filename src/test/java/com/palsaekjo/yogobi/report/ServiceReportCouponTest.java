package com.palsaekjo.yogobi.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.palsaekjo.yogobi.user.AuthTokens;
import com.palsaekjo.yogobi.user.TestMembers;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 제보 리워드 쿠폰(V24). 여기서 지키는 것은 넷이다 —
 * ① 비로그인 제보도 쿠폰을 받는다(익명 유지) ② 로그인 제보는 회원에 귀속돼 쿠폰함에 뜬다
 * ③ 남의 쿠폰은 보이지 않는다 ④ 탈퇴하면 제보 본문은 남고 귀속만 끊긴다.
 *
 * <p>접수 자체의 검증(CSRF·입력값·조회 불가)은 {@code ServiceReportTest} 의 몫이라 다시 하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ServiceReportCouponTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthTokens tokens;
    MockHttpSession session;
    String csrf;

    static final String BODY = """
            {"category":"SYSTEM","description":"결과 화면에서 다음 버튼이 눌리지 않아요","pageUrl":"/results"}
            """;

    @BeforeEach void setUp() throws Exception {
        jdbc.execute("DELETE FROM service_report");
        jdbc.execute("DELETE FROM auth_rate_limit");
        jdbc.execute("DELETE FROM app_user");
        var response = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        session = (MockHttpSession) response.getRequest().getSession(false);
        csrf = new ObjectMapper().readTree(response.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    MockHttpServletRequestBuilder report(Cookie... auth) {
        var builder = post("/api/v1/reports").session(session).header("X-CSRF-TOKEN", csrf)
                .contentType("application/json").content(BODY);
        return auth.length == 0 ? builder : builder.cookie(auth);
    }

    @Test void guestGetsCouponAndStaysAnonymous() throws Exception {
        String body = mvc.perform(report()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coupon.status").value("UNUSED"))
                .andReturn().getResponse().getContentAsString();
        var data = new ObjectMapper().readTree(body).path("data");
        // 쿠폰 코드는 접수증 id 와 같은 값이다 — 제보 1건 = 쿠폰 1장이라 코드를 따로 두지 않았다.
        assertThat(data.path("coupon").path("code").asText()).isEqualTo(data.path("id").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_report WHERE user_id IS NULL", Integer.class))
                .isEqualTo(1);
    }

    @Test void memberCouponLandsInTheirCouponBox() throws Exception {
        Cookie[] auth = TestMembers.login(jdbc, tokens, "reporter@example.com");
        String body = mvc.perform(report(auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String code = new ObjectMapper().readTree(body).path("data").path("coupon").path("code").asText();

        mvc.perform(get("/api/v1/me/coupons").cookie(auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value(code))
                .andExpect(jsonPath("$.data[0].status").value("UNUSED"))
                .andExpect(jsonPath("$.data[0].usedAt").doesNotExist());
    }

    @Test void anotherMemberSeesNothing() throws Exception {
        mvc.perform(report(TestMembers.login(jdbc, tokens, "reporter@example.com"))).andExpect(status().isOk());
        Cookie[] stranger = TestMembers.login(jdbc, tokens, "stranger@example.com");
        mvc.perform(get("/api/v1/me/coupons").cookie(stranger)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        // 비회원은 쿠폰함 자체에 닿지 못한다.
        mvc.perform(get("/api/v1/me/coupons")).andExpect(status().isUnauthorized());
    }

    @Test void leavingKeepsTheBugReportAndDropsOnlyTheOwnership() throws Exception {
        long id = TestMembers.create(jdbc, "quitter@example.com");
        mvc.perform(report(TestMembers.session(tokens, id))).andExpect(status().isOk());

        jdbc.update("DELETE FROM app_user WHERE id = ?", id);

        // 제보 본문은 우리 버그 기록이라 남는다. 회원과의 연결만 끊긴다(V24 의 ON DELETE SET NULL).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_report", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_id FROM service_report", Long.class)).isNull();
    }
}
