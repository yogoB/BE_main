package com.palsaekjo.yogobi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * 백오피스(D-32): 아이디 로그인 → 지표 → 수집 검수. 별도 서버 없이 이 서버가 다 한다.
 * 실제 보안 필터·CSRF·쿠키를 그대로 태운다.
 */
@SpringBootTest(properties = {
        "JWT_SECRET=VHlwZS1vbmx5LXRlc3Qta2V5LTMyaGFyYWN0ZXJzLW9yLW1vcmUh",
        "AUTH_SECURE_COOKIES=false", "AUTH_SESSION_COOKIE_NAME=YGB_SESSION",
        "ADMIN_ID=yogogo", "ADMIN_PASSWORD=test-backoffice-password",
        "CATALOG_ADMIN_USER_IDS=",                      // 허용목록 없이 관리자 계정만으로 운영자여야 한다
        "yogobi.harvest.cron=0 0 9 1 1 ?"})             // 테스트 중 자동 실행 방지(1월 1일)
@AutoConfigureMockMvc
@Testcontainers
class BackofficeApiTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_PASSWORD = "test-backoffice-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path csv = directory.resolve("catalog_combined.csv");
        Files.writeString(csv, new ClassPathResource("db/seed/catalog_combined.csv")
                .getContentAsString(StandardCharsets.UTF_8));
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("yogobi.catalog.combined-csv", () -> csv.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogDailyHarvest harvest;

    /* ---- 인증 ---- */

    @Test
    void backofficeIsClosedWithoutLogin() throws Exception {
        mvc.perform(get("/api/v1/admin/session")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/catalog/requests")).andExpect(status().isUnauthorized());
        send(post("/api/v1/admin/harvest/run"), new Cookie[0]).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCredentialsAreRejectedWithoutRevealingTheId() throws Exception {
        send(login("yogogo", "틀린비밀번호"), new Cookie[0])
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("YGB-AUTH-001"));
        send(login("없는아이디", ADMIN_PASSWORD), new Cookie[0])
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("YGB-AUTH-001"));   // 같은 응답이어야 한다
    }

    /** 관리자는 허용목록(CATALOG_ADMIN_USER_IDS)이 비어 있어도 운영자다. */
    @Test
    void adminLoginOpensDashboardAndReview() throws Exception {
        Cookie[] admin = loginAsAdmin();

        mvc.perform(get("/api/v1/admin/session").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.admin").value(true))
                .andExpect(jsonPath("$.data.loginId").value("yogogo"));
        mvc.perform(get("/api/v1/admin/dashboard").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.members.total").isNumber())
                .andExpect(jsonPath("$.data.catalog.mobilePlans").isNumber())
                .andExpect(jsonPath("$.data.signupTrend.length()").value(7))
                .andExpect(jsonPath("$.data.endpoints").isArray());
        mvc.perform(get("/api/v1/admin/catalog/requests").cookie(admin)).andExpect(status().isOk());
    }

    /** 일반 회원 쿠키로는 백오피스가 열리지 않는다. */
    @Test
    void ordinaryMemberCannotEnterBackoffice() throws Exception {
        Cookie[] member = signupMember("member-backoffice@example.com");
        mvc.perform(get("/api/v1/admin/session").cookie(member)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/dashboard").cookie(member)).andExpect(status().isForbidden());
    }

    /* ---- 수집 → 검수 ---- */

    /** 스냅샷이 우리와 다른 금액을 담고 있으면 수집이 **제안**을 만든다(바로 반영하지 않는다). */
    @Test
    void harvestTurnsPriceDifferencesIntoPendingProposals() throws Exception {
        var plan = jdbc.queryForMap("""
                SELECT c.name AS carrier, m.name AS plan_name, m.base_price
                FROM mobile_plan m JOIN carrier c ON c.id = m.carrier_id WHERE m.active LIMIT 1""");
        long ours = ((Number) plan.get("base_price")).longValue();
        long theirs = ours + 5000;
        jdbc.update("""
                INSERT INTO smartchoice_plan_snapshot
                    (carrier, plan_name, network_type, contract_months, plan_price, discounted_price, source_url)
                VALUES (?, ?, '5G', 0, ?, ?, 'https://api.smartchoice.or.kr/api/openAPI.xml')""",
                plan.get("carrier"), plan.get("plan_name"), theirs, theirs);

        var result = harvest.harvest();
        assertThat((Integer) result.get("proposedMobilePlans")).isEqualTo(1);

        String key = plan.get("carrier") + "|" + plan.get("plan_name");
        var request = jdbc.queryForMap("""
                SELECT * FROM catalog_change_request WHERE dataset = 'mobile_plan' AND row_key = ?""", key);
        assertThat(request).containsEntry("status", "PENDING").containsEntry("action", "UPDATE");
        assertThat((String) request.get("payload")).contains(String.valueOf(theirs));
        assertThat((String) request.get("reason")).contains("일일 수집");
        // 아직 카탈로그는 그대로다 — 승인해야 바뀐다(D-28).
        assertThat(jdbc.queryForObject("SELECT base_price FROM mobile_plan WHERE name = ?", Long.class,
                plan.get("plan_name"))).isEqualTo(ours);

        // 같은 대상을 두 번 쌓지 않는다.
        assertThat((Integer) harvest.harvest().get("proposedMobilePlans")).isZero();
    }

    /** 검수함에서 승인하면 그때 카탈로그가 바뀐다 — 백오피스가 실제로 동작하는지 끝까지 본다. */
    @Test
    void operatorApprovesHarvestedProposalFromBackoffice() throws Exception {
        Cookie[] admin = loginAsAdmin();
        var tier = jdbc.queryForMap("SELECT id, price FROM subscription_tier WHERE active ORDER BY id LIMIT 1");
        long id = ((Number) tier.get("id")).longValue();
        long changed = ((Number) tier.get("price")).longValue() + 1100;

        long requestId = requestId(send(post("/api/v1/admin/catalog/{dataset}/{key}",
                "subscription_tier", String.valueOf(id))
                .with(request -> { request.setMethod("PATCH"); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":\"" + changed + "\",\"reason\":\"백오피스 검수 확인\"}"), admin)
                .andExpect(status().isAccepted()));

        send(post("/api/v1/admin/catalog/requests/" + requestId + "/approve"), admin)
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT price FROM subscription_tier WHERE id = ?", Long.class, id))
                .isEqualTo(changed);
    }

    /* ---- helpers ---- */

    private MockHttpServletRequestBuilder login(String id, String password) throws Exception {
        return post("/api/v1/admin/login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsBytes(Map.of("id", id, "password", password)));
    }

    private Cookie[] loginAsAdmin() throws Exception {
        return send(login("yogogo", ADMIN_PASSWORD), new Cookie[0])
                .andExpect(status().isOk()).andReturn().getResponse().getCookies();
    }

    private Cookie[] signupMember(String email) throws Exception {
        var body = Map.of("name", "회원", "email", email, "password", "Passw0rd!seed-backoffice-member",
                "nickname", email.split("@")[0]);
        var request = post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsBytes(body));
        return send(request, new Cookie[0]).andExpect(status().isOk()).andReturn().getResponse().getCookies();
    }

    private long requestId(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString())
                .path("data").path("requestId").asLong();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Cookie[] cookies) throws Exception {
        var session = new MockHttpSession();
        var issued = mvc.perform(withCookies(get("/api/v1/auth/csrf").session(session), cookies))
                .andExpect(status().isOk()).andReturn();
        String token = JSON.readTree(issued.getResponse().getContentAsString())
                .path("data").path("token").asText();
        return mvc.perform(withCookies(request.session(session), cookies).header("X-CSRF-TOKEN", token));
    }

    private static MockHttpServletRequestBuilder withCookies(MockHttpServletRequestBuilder request, Cookie[] cookies) {
        return cookies.length == 0 ? request : request.cookie(cookies);
    }
}
