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
    @Autowired com.palsaekjo.yogobi.user.AuthTokens tokens;
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
                .andExpect(jsonPath("$.data.weeklyActivity.length()").value(7))
                // 가입만으로는 대부분 0 이라 운영 활동을 같이 낸다 — 네 계열이 모두 있어야 한다.
                .andExpect(jsonPath("$.data.weeklyActivity[0].signups").isNumber())
                .andExpect(jsonPath("$.data.weeklyActivity[0].reports").isNumber())
                .andExpect(jsonPath("$.data.weeklyActivity[0].proposals").isNumber())
                .andExpect(jsonPath("$.data.weeklyActivity[0].applied").isNumber())
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

    /**
     * G-55 — 스마트초이스가 확인한 청년 파생형 결손은 자동 검토 제안이 되고,
     * 운영자가 승인한 뒤에만 합본 CSV와 DB에 함께 들어간다.
     */
    @Test
    void requestedYouthPlanBecomesAReviewedProposalThenCsvAndDatabase() throws Exception {
        String planName = "베이직21GB Y덤";
        String key = "KT|" + planName;
        jdbc.update("DELETE FROM catalog_change_request WHERE dataset = 'mobile_plan' AND row_key = ?", key);
        jdbc.update("DELETE FROM catalog_candidate WHERE kind = 'MOBILE_PLAN' AND query_text = ?", "KT " + planName);
        jdbc.update("DELETE FROM smartchoice_plan_snapshot WHERE carrier = 'KT' AND plan_name = ?", planName);
        jdbc.update("INSERT INTO catalog_candidate(kind, query_text, status) VALUES ('MOBILE_PLAN', ?, 'REQUESTED')",
                "KT " + planName);
        jdbc.update("""
                INSERT INTO smartchoice_plan_snapshot
                    (carrier, plan_name, network_type, contract_months, plan_price, discounted_price,
                     display_data, source_url, collected_at)
                VALUES ('KT', ?, '5G', 0, 58000, 0, '42GB + 1Mbps 속도제어',
                        'https://www.smartchoice.or.kr/', now())
                """, planName);

        harvest.harvest();

        Map<String, Object> proposal = jdbc.queryForMap(
                "SELECT id, action, payload::text, review_status FROM catalog_change_request "
                        + "WHERE dataset = 'mobile_plan' AND row_key = ?", key);
        assertThat(proposal).containsEntry("action", "CREATE").containsEntry("review_status", "VERIFIED");
        assertThat((String) proposal.get("payload"))
                .contains("\"data_mb\":\"43008\"")
                .contains("\"age_limit\":\"청년\"")
                .contains("https://product.kt.com/wDic/index.do");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM catalog_candidate WHERE kind = 'MOBILE_PLAN' AND query_text = ?",
                String.class, "KT " + planName)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mobile_plan WHERE name = ?", Integer.class, planName))
                .isZero();

        long requestId = ((Number) proposal.get("id")).longValue();
        send(post("/api/v1/admin/catalog/requests/" + requestId + "/approve"), loginAsAdmin())
                .andExpect(status().isOk());

        assertThat(jdbc.queryForMap("SELECT data_mb, age_limit FROM mobile_plan WHERE name = ?", planName))
                .containsEntry("data_mb", 43008L).containsEntry("age_limit", "청년");
        assertThat(Files.readString(directory.resolve("catalog_combined.csv")))
                .contains("KT," + planName + ",5G/LTE,58000,43008");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM catalog_candidate WHERE kind = 'MOBILE_PLAN' AND query_text = ?",
                String.class, "KT " + planName)).isEqualTo("VERIFIED");
    }

    /** G-55 — SKT의 명시적인 (청년) 파생형과 소수 GB 표기도 같은 안전 경로를 탄다. */
    @Test
    void requestedSktYouthPlanUsesItsOfficialBaseRowAndRoundsDecimalGb() {
        String baseName = "G55 라이트";
        String planName = baseName + " (청년)";
        String key = "SKT|" + planName;
        Long carrierId = jdbc.queryForObject("SELECT id FROM carrier WHERE name = 'SKT'", Long.class);
        jdbc.update("DELETE FROM catalog_change_request WHERE dataset = 'mobile_plan' AND row_key = ?", key);
        jdbc.update("DELETE FROM catalog_candidate WHERE kind = 'MOBILE_PLAN' AND query_text = ?", "SKT " + planName);
        jdbc.update("DELETE FROM smartchoice_plan_snapshot WHERE carrier = 'SKT' AND plan_name = ?", planName);
        jdbc.update("DELETE FROM mobile_plan WHERE carrier_id = ? AND name IN (?, ?)", carrierId, baseName, planName);
        try {
            jdbc.update("""
                    INSERT INTO mobile_plan
                        (carrier_id, name, network_type, base_price, data_mb, age_limit, source_url, collected_at)
                    VALUES (?, ?, 'FIVE_G', 10000, 1024, 'ALL',
                            'https://m.tworld.co.kr/product/renewal/mobileplan/list', DATE '2026-09-20')
                    """, carrierId, baseName);
            jdbc.update("INSERT INTO catalog_candidate(kind, query_text, status) VALUES ('MOBILE_PLAN', ?, 'REQUESTED')",
                    "SKT " + planName);
            jdbc.update("""
                    INSERT INTO smartchoice_plan_snapshot
                        (carrier, plan_name, network_type, contract_months, plan_price, discounted_price,
                         display_data, source_url, collected_at)
                    VALUES ('SKT', ?, '5G', 0, 10000, 0, '1.4GB + 400kbps 속도제어',
                            'https://www.smartchoice.or.kr/', now())
                    """, planName);

            harvest.harvest();

            Map<String, Object> proposal = jdbc.queryForMap(
                    "SELECT payload::text, review_status FROM catalog_change_request "
                            + "WHERE dataset = 'mobile_plan' AND row_key = ?", key);
            assertThat(proposal).containsEntry("review_status", "VERIFIED");
            assertThat((String) proposal.get("payload"))
                    .contains("\"data_mb\":\"1434\"")
                    .contains("https://m.tworld.co.kr/product/renewal/mobileplan/list");
        } finally {
            jdbc.update("DELETE FROM catalog_change_request WHERE dataset = 'mobile_plan' AND row_key = ?", key);
            jdbc.update("DELETE FROM smartchoice_plan_snapshot WHERE carrier = 'SKT' AND plan_name = ?", planName);
            jdbc.update("DELETE FROM catalog_candidate WHERE kind = 'MOBILE_PLAN' AND query_text = ?", "SKT " + planName);
            jdbc.update("DELETE FROM mobile_plan WHERE carrier_id = ? AND name IN (?, ?)", carrierId, baseName, planName);
        }
    }

    /** G-55-e — 데이터를 MB로 확정할 수 없으면 제안을 지어내지 않는다. */
    @Test
    void unparseableYouthPlanRemainsARequestedGap() {
        String baseName = "G55 파서 원본";
        String planName = baseName + " Y덤";
        String key = "KT|" + planName;
        Long carrierId = jdbc.queryForObject("SELECT id FROM carrier WHERE name = 'KT'", Long.class);
        jdbc.update("DELETE FROM catalog_change_request WHERE dataset = 'mobile_plan' AND row_key = ?", key);
        jdbc.update("DELETE FROM catalog_candidate WHERE kind = 'MOBILE_PLAN' AND query_text = ?", "KT " + planName);
        jdbc.update("DELETE FROM smartchoice_plan_snapshot WHERE carrier = 'KT' AND plan_name = ?", planName);
        jdbc.update("DELETE FROM mobile_plan WHERE carrier_id = ? AND name IN (?, ?)", carrierId, baseName, planName);
        try {
            jdbc.update("""
                    INSERT INTO mobile_plan
                        (carrier_id, name, network_type, base_price, data_mb, age_limit, source_url, collected_at)
                    VALUES (?, ?, 'LTE_5G', 10000, 1024, 'ALL',
                            'https://product.kt.com/wDic/index.do', DATE '2026-09-20')
                    """, carrierId, baseName);
            jdbc.update("INSERT INTO catalog_candidate(kind, query_text, status) VALUES ('MOBILE_PLAN', ?, 'REQUESTED')",
                    "KT " + planName);
            jdbc.update("""
                    INSERT INTO smartchoice_plan_snapshot
                        (carrier, plan_name, network_type, contract_months, plan_price, discounted_price,
                         display_data, source_url, collected_at)
                    VALUES ('KT', ?, '5G', 0, 10000, 0, '확인 필요', 'https://www.smartchoice.or.kr/', now())
                    """, planName);

            harvest.harvest();

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM catalog_change_request WHERE dataset = 'mobile_plan' AND row_key = ?",
                    Integer.class, key)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM catalog_candidate WHERE kind = 'MOBILE_PLAN' AND query_text = ?",
                    String.class, "KT " + planName)).isEqualTo("REQUESTED");
        } finally {
            jdbc.update("DELETE FROM smartchoice_plan_snapshot WHERE carrier = 'KT' AND plan_name = ?", planName);
            jdbc.update("DELETE FROM catalog_candidate WHERE kind = 'MOBILE_PLAN' AND query_text = ?", "KT " + planName);
            jdbc.update("DELETE FROM mobile_plan WHERE carrier_id = ? AND name IN (?, ?)", carrierId, baseName, planName);
        }
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
        // D-34: 가입은 Google 하나뿐이다. 여기서 필요한 것은 로그인한 회원뿐이라 직접 만든다.
        return com.palsaekjo.yogobi.user.TestMembers.login(jdbc, tokens, email);
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
