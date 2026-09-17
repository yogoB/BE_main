package com.palsaekjo.yogobi.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** 상품 없는 제보(D-41). CatalogReportTest 와 같은 규칙 — CSRF 필수, 접수만, 조회 불가. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ServiceReportTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    MockHttpSession session;
    String token;
    static final String BODY = """
            {"category":"SYSTEM","description":"결과 화면에서 다음 버튼이 눌리지 않아요","pageUrl":"/results"}
            """;

    @BeforeEach void csrf() throws Exception {
        jdbc.execute("DELETE FROM service_report");
        jdbc.execute("DELETE FROM auth_rate_limit");
        var response = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        session = (MockHttpSession) response.getRequest().getSession(false);
        token = new ObjectMapper().readTree(response.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    MockHttpServletRequestBuilder report(String body) {
        return post("/api/v1/reports").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(body);
    }

    @Test void guestCanReportWithCsrfAndNobodyCanRead() throws Exception {
        mvc.perform(post("/api/v1/reports").contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        mvc.perform(report(BODY)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"));
        assertThat(jdbc.queryForObject("SELECT page_url FROM service_report", String.class)).isEqualTo("/results");
        // GET 은 공개 목록에 없어 인증부터 막힌다 — 제보 본문은 누구도 API 로 읽지 못한다.
        mvc.perform(get("/api/v1/reports")).andExpect(status().isUnauthorized());
    }

    @Test void invalidInputsNeverCreateReport() throws Exception {
        for (String invalid : new String[]{BODY.replace("SYSTEM", "PRICE"), BODY.replace("SYSTEM", ""),
                BODY.replace("결과 화면에서 다음 버튼이 눌리지 않아요", " "),
                BODY.replace("/results", "https://evil.example/phish"),
                BODY.replace("}", ",\"sourceUrl\":\"http://insecure.example\"}")}) {
            mvc.perform(report(invalid)).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_report", Integer.class)).isZero();
    }
}
