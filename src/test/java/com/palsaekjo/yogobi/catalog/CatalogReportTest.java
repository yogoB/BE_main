package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.palsaekjo.yogobi.privacy.RetentionService;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CatalogReportTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RetentionService retention;
    MockHttpSession session;
    String token;
    static final String BODY = """
            {"targetType":"SUBSCRIPTION_TIER","targetId":2,"field":"PRICE",
             "description":"공식 월요금과 달라요","sourceUrl":"https://example.com/price"}
            """;
    @BeforeEach void csrf() throws Exception {
        jdbc.execute("DELETE FROM catalog_report");
        jdbc.execute("DELETE FROM auth_rate_limit");
        var response = mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        session = (MockHttpSession) response.getRequest().getSession(false);
        token = new ObjectMapper().readTree(response.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder report(String body) {
        return post("/api/v1/catalog/reports").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(body);
    }

    @Test void guestCanReportWithCsrfButCannotChangeCatalogOrReadReports() throws Exception {
        long before = jdbc.queryForObject("SELECT price FROM subscription_tier WHERE id=2", Long.class);
        mvc.perform(post("/api/v1/catalog/reports").contentType("application/json").content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(report(BODY)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.id").isString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_report", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT price FROM subscription_tier WHERE id=2", Long.class)).isEqualTo(before);
        mvc.perform(get("/api/v1/catalog/reports")).andExpect(status().isMethodNotAllowed());
    }

    @Test void invalidTargetAndInputsNeverCreateReport() throws Exception {
        mvc.perform(report(BODY.replace("\"targetId\":2", "\"targetId\":999999"))).andExpect(status().isNotFound());
        for (String invalid : new String[]{BODY.replace("SUBSCRIPTION_TIER", "subscription_tier; DROP TABLE app_user"),
                BODY.replace("공식 월요금과 달라요", " "), BODY.replace("공식 월요금과 달라요", "x".repeat(2001)),
                BODY.replace("https://example.com/price", "javascript:alert(1)"),
                BODY.replace("https://example.com/price", "https://example.com/price?token=secret")})
            mvc.perform(report(invalid)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_report", Integer.class)).isZero();
    }

    @Test void limitsReportsAndPurgesAfterRetention() throws Exception {
        for (int i=0; i<5; i++) mvc.perform(report(BODY)).andExpect(status().isOk());
        mvc.perform(report(BODY)).andExpect(status().isTooManyRequests());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_report", Integer.class)).isEqualTo(5);
        jdbc.execute("UPDATE catalog_report SET created_at=now()-interval '91 days' WHERE id=(SELECT id FROM catalog_report LIMIT 1)");
        assertThat(retention.purge().get("catalog_report")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_report", Integer.class)).isEqualTo(4);
    }
}
