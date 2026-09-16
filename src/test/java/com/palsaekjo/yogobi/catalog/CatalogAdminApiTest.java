package com.palsaekjo.yogobi.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 카탈로그 원본 CRUD API는 **운영자 전용**이어야 한다(D-24).
 * 공개 읽기(`/api/v1/catalog/**`)는 permitAll 이고 로그인만 하면 누구나 회원이므로,
 * 경로 분리 + 운영자 허용목록 둘 다 확인한다. 실제 가입·로그인·CSRF 필터를 그대로 태운다.
 */
@SpringBootTest(properties = {
        "JWT_SECRET=VHlwZS1vbmx5LXRlc3Qta2V5LTMyaGFyYWN0ZXJzLW9yLW1vcmUh",
        "yogobi.auth.email-enabled=false",
        "AUTH_SECURE_COOKIES=false", "AUTH_SESSION_COOKIE_NAME=YGB_SESSION",
        "CATALOG_ADMIN_USER_IDS=1"})     // 첫 가입자만 운영자
@AutoConfigureMockMvc
@Testcontainers
class CatalogAdminApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void anonymousCannotReadOrWriteTheCatalogSource() throws Exception {
        mvc.perform(get("/api/v1/admin/catalog")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/catalog/mobile_plan")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/catalog/mobile_plan")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"carrier\":\"SKT\"}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(patch("/api/v1/admin/catalog/mobile_plan/SKT%7C요고")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"base_price\":\"1\"}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(delete("/api/v1/admin/catalog/mobile_plan/SKT%7C요고"))
                .andExpect(status().is4xxClientError());
    }

    /** 이번 변경의 핵심: 로그인한 **일반 회원**은 카탈로그 원본을 읽지도 고치지도 못한다. */
    @Test
    void ordinaryMemberIsForbidden() throws Exception {
        Cookie[] member = signup("member@example.com");
        long id = userId("member@example.com");
        org.assertj.core.api.Assertions.assertThat(id).isNotEqualTo(1L); // 운영자 목록(1)에 없는 회원

        mvc.perform(get("/api/v1/admin/catalog").cookie(member)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/catalog/mobile_plan").cookie(member)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/catalog/mobile_plan/SKT%7C요고").cookie(member))
                .andExpect(status().is4xxClientError());
        // 회원 기능 자체는 그대로 쓸 수 있어야 한다 — ADMIN 분리가 기존 권한을 깨지 않았는지.
        mvc.perform(get("/api/v1/me").cookie(member)).andExpect(status().isOk());
    }

    /** 허용목록에 있는 회원만 읽기가 열린다. */
    @Test
    void operatorCanReadTheCatalogSource() throws Exception {
        Cookie[] operator = signup("operator@example.com");     // 첫 가입자 → id 1
        org.assertj.core.api.Assertions.assertThat(userId("operator@example.com")).isEqualTo(1L);

        mvc.perform(get("/api/v1/admin/catalog").cookie(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].dataset").value("mobile_plan"));
    }

    @Test
    void publicCatalogReadStaysOpen() throws Exception {
        mvc.perform(get("/api/v1/catalog/services")).andExpect(status().isOk());
    }

    /** 직접 가입(D-20: 메일 비활성)으로 세션 쿠키를 얻는다. CSRF 토큰도 실제 흐름대로 받아 붙인다. */
    private Cookie[] signup(String email) throws Exception {
        var session = new MockHttpSession();
        var csrfResult = mvc.perform(get("/api/v1/auth/csrf").session(session))
                .andExpect(status().isOk()).andReturn();
        String token = JSON.readTree(csrfResult.getResponse().getContentAsString())
                .path("data").path("token").asText();
        var body = Map.of("name", "운영자", "email", email, "password", "Passw0rd!seed-catalog-operator", "nickname", email.split("@")[0]);
        MockHttpServletRequestBuilder request = post("/api/v1/auth/signup")
                .session(session).header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(body));
        var res = mvc.perform(request).andReturn().getResponse();
        if (res.getStatus() != 200) throw new IllegalStateException("signup " + res.getStatus() + ": " + res.getContentAsString());
        return res.getCookies();
    }

    private long userId(String email) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
    }
}
