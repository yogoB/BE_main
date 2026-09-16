package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
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
 * 카탈로그 원본 변경 API — 운영자 전용(D-24)이고 **제안·승인 2단계**(D-28)다.
 * 실제 가입·CSRF·JWT 필터를 그대로 태운다. 쓰기는 승인 전까지 파일·DB를 건드리지 않아야 한다.
 */
@SpringBootTest(properties = {
        "JWT_SECRET=VHlwZS1vbmx5LXRlc3Qta2V5LTMyaGFyYWN0ZXJzLW9yLW1vcmUh",
        "yogobi.auth.email-enabled=false",
        "AUTH_SECURE_COOKIES=false", "AUTH_SESSION_COOKIE_NAME=YGB_SESSION",
        "CATALOG_ADMIN_USER_IDS=1"})     // 첫 가입자만 운영자
@AutoConfigureMockMvc
@Testcontainers
@org.springframework.context.annotation.Import(CatalogAdminApiTest.MismatchingOracle.class)
class CatalogAdminApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "Passw0rd!seed-catalog-operator";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @TempDir
    static Path directory;
    static Path csv;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        csv = directory.resolve("catalog_combined.csv");
        Files.writeString(csv, new ClassPathResource("db/seed/catalog_combined.csv")
                .getContentAsString(StandardCharsets.UTF_8));
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("yogobi.catalog.combined-csv", () -> csv.toString());
    }

    private static final String OPERATOR = "operator@example.com";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    /** 운영자는 `CATALOG_ADMIN_USER_IDS=1` 이므로 **가장 먼저 가입한 계정**이어야 한다. 테스트 순서와 무관하게 고정한다. */
    @org.junit.jupiter.api.BeforeEach
    void operatorIsTheFirstMember() throws Exception {
        if (!exists(OPERATOR)) signup(OPERATOR);
    }

    /* ---- 권한: 익명·일반 회원은 닿을 수 없다(D-24) ---- */

    @Test
    void anonymousCannotReadOrWriteTheCatalogSource() throws Exception {
        mvc.perform(get("/api/v1/admin/catalog")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/catalog/audit")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/catalog/requests")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/catalog/mobile_plan")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"carrier\":\"SKT\"}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(delete("/api/v1/admin/catalog/{dataset}/{key}", "mobile_plan", "SKT|요고"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void ordinaryMemberIsForbidden() throws Exception {
        Cookie[] member = signup("member@example.com");
        assertThat(userId("member@example.com")).isNotEqualTo(1L);   // 운영자 목록(1)에 없다

        mvc.perform(get("/api/v1/admin/catalog").cookie(member)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/catalog/audit").cookie(member)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/catalog/requests").cookie(member)).andExpect(status().isForbidden());
        send(post("/api/v1/admin/catalog/requests/1/approve"), member).andExpect(status().isForbidden());
        send(post("/api/v1/admin/catalog/mobile_plan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"carrier\":\"SKT\",\"plan_name\":\"몰래요고\"}"), member)
                .andExpect(status().isForbidden());
        // 회원 기능 자체는 그대로다 — ADMIN 분리가 기존 권한을 깨지 않았는지.
        mvc.perform(get("/api/v1/me").cookie(member)).andExpect(status().isOk());
    }

    @Test
    void operatorCanReadTheCatalogSource() throws Exception {
        Cookie[] operator = login(OPERATOR);
        assertThat(userId(OPERATOR)).isEqualTo(1L);

        mvc.perform(get("/api/v1/admin/catalog").cookie(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].dataset").value("mobile_plan"));
        mvc.perform(get("/api/v1/admin/catalog/audit").cookie(operator)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/catalog/requests").cookie(operator)).andExpect(status().isOk());
    }

    @Test
    void publicCatalogReadStaysOpen() throws Exception {
        mvc.perform(get("/api/v1/catalog/services")).andExpect(status().isOk());
    }

    /* ---- 제안·승인 2단계(D-28): 승인 전에는 아무것도 바뀌지 않는다 ---- */

    @Test
    void proposalChangesNothingUntilApproved() throws Exception {
        Cookie[] operator = login(OPERATOR);
        String before = Files.readString(csv);
        int auditBefore = auditCount();

        long requestId = requestId(send(post("/api/v1/admin/catalog/mobile_plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"carrier":"SKT","plan_name":"승인대기요고","network_type":"5G","base_price":"31000",
                         "data_mb":"5120","source_url":"https://m.tworld.co.kr/plan","collected_at":"2026-09-16",
                         "reason":"신규 요금제 반영"}"""), operator)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PENDING")));

        // 제안만으로는 원본도 DB도 감사 기록도 그대로다.
        assertThat(Files.readString(csv)).isEqualTo(before);
        assertThat(planCount("승인대기요고")).isZero();
        assertThat(auditCount()).isEqualTo(auditBefore);
        assertThat(jdbc.queryForList("SELECT * FROM catalog_change_request WHERE id = ?", requestId).get(0))
                .containsEntry("status", "PENDING").containsEntry("reason", "신규 요금제 반영");

        send(post("/api/v1/admin/catalog/requests/" + requestId + "/approve"), operator)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        // 승인해야 반영된다.
        assertThat(Files.readString(csv)).contains("SKT,승인대기요고,5G,31000");
        assertThat(planCount("승인대기요고")).isEqualTo(1);

        // 감사 기록에 요청번호·제안자·승인자가 함께 남는다.
        var entry = jdbc.queryForList("SELECT * FROM catalog_audit ORDER BY id DESC LIMIT 1").get(0);
        assertThat(entry).containsEntry("outcome", "APPLIED").containsEntry("action", "CREATE");
        assertThat((String) entry.get("detail"))
                .contains("요청#" + requestId).contains("제안자=").contains("승인자=");

        // 같은 제안을 두 번 반영할 수 없다.
        send(post("/api/v1/admin/catalog/requests/" + requestId + "/approve"), operator)
                .andExpect(status().isConflict());
    }

    @Test
    void rejectedProposalIsNeverApplied() throws Exception {
        Cookie[] operator = login(OPERATOR);
        String before = Files.readString(csv);
        int auditBefore = auditCount();

        long requestId = requestId(send(patch("/api/v1/admin/catalog/{dataset}/{key}", "mobile_plan", "SKT|베스트 Max(T 우주)")
                .contentType(MediaType.APPLICATION_JSON).content("{\"base_price\":\"1\"}"), operator)
                .andExpect(status().isAccepted()));

        send(post("/api/v1/admin/catalog/requests/" + requestId + "/reject")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"근거 자료 없음\"}"), operator)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        assertThat(Files.readString(csv)).isEqualTo(before);      // 원본 불변
        assertThat(auditCount()).isEqualTo(auditBefore);          // 반영이 없었으니 감사 기록도 없다
        var request = jdbc.queryForList("SELECT * FROM catalog_change_request WHERE id = ?", requestId).get(0);
        assertThat(request).containsEntry("status", "REJECTED").containsEntry("decision_note", "근거 자료 없음");
        assertThat(request.get("decided_by")).isNotNull();
    }

    /** 검토에서 불일치가 확인된 제안은 승인되지 않는다(D-29). 스텁 소스로 MISMATCH 를 만든다. */
    @Test
    void mismatchedProposalCannotBeApproved() throws Exception {
        Cookie[] operator = login(OPERATOR);
        String before = Files.readString(csv);

        long requestId = requestId(send(post("/api/v1/admin/catalog/mobile_plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"carrier":"SKT","plan_name":"불일치요고","network_type":"5G","base_price":"31000",
                         "data_mb":"5120","source_url":"https://m.tworld.co.kr/plan","collected_at":"2026-09-16"}"""),
                operator).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.reviewStatus").value("MISMATCH")));

        send(post("/api/v1/admin/catalog/requests/" + requestId + "/approve"), operator)
                .andExpect(status().isConflict());
        assertThat(Files.readString(csv)).isEqualTo(before);   // 반영되지 않는다
    }

    /** 검토 소스 스텁: 제안 금액과 다른 값을 보고해 MISMATCH 를 만든다. */
    @org.springframework.boot.test.context.TestConfiguration
    static class MismatchingOracle {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        PriceOracle stub() {
            return (carrier, plan, data, network) ->
                    "불일치요고".equals(plan) ? java.util.Optional.of(99000L) : java.util.Optional.empty();
        }
    }

    /* ---- helpers ---- */

    private long requestId(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString())
                .path("data").path("requestId").asLong();
    }

    /** CSRF 토큰을 실제 흐름대로 받아 붙여 보낸다(변경 요청은 헤더가 필수다). */
    private ResultActions send(MockHttpServletRequestBuilder request, Cookie[] cookies) throws Exception {
        var session = new MockHttpSession();
        var issued = mvc.perform(withCookies(get("/api/v1/auth/csrf").session(session), cookies))
                .andExpect(status().isOk()).andReturn();
        String token = JSON.readTree(issued.getResponse().getContentAsString())
                .path("data").path("token").asText();
        return mvc.perform(withCookies(request.session(session), cookies).header("X-CSRF-TOKEN", token));
    }

    /** MockMvc 는 빈 쿠키 배열을 거부한다 — 비로그인 요청은 쿠키를 아예 붙이지 않는다. */
    private static MockHttpServletRequestBuilder withCookies(MockHttpServletRequestBuilder request, Cookie[] cookies) {
        return cookies.length == 0 ? request : request.cookie(cookies);
    }

    /** 직접 가입(D-20: 메일 비활성)으로 세션 쿠키를 얻는다. */
    private Cookie[] signup(String email) throws Exception {
        var body = Map.of("name", "운영자", "email", email, "password", PASSWORD,
                "nickname", email.split("@")[0]);
        var request = post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsBytes(body));
        var response = send(request, new Cookie[0]).andReturn().getResponse();
        if (response.getStatus() != 200)
            throw new IllegalStateException("signup " + response.getStatus() + ": " + response.getContentAsString());
        return response.getCookies();
    }

    private boolean exists(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Integer.class, email) > 0;
    }

    private Cookie[] login(String email) throws Exception {
        var body = Map.of("email", email, "password", PASSWORD);
        var request = post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsBytes(body));
        var response = send(request, new Cookie[0]).andExpect(status().isOk()).andReturn().getResponse();
        return response.getCookies();
    }

    private long userId(String email) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
    }

    private int planCount(String name) {
        return jdbc.queryForObject("SELECT count(*) FROM mobile_plan WHERE name = ?", Integer.class, name);
    }

    private int auditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM catalog_audit", Integer.class);
    }
}
