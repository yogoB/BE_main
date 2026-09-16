package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.palsaekjo.yogobi.user.AuthTokens;
import com.palsaekjo.yogobi.user.TestMembers;
import jakarta.servlet.http.Cookie;
import java.util.Map;
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
 * **운영 설정 그대로 도는 테스트다 — `yogobi.catalog.combined-csv` 를 일부러 설정하지 않는다.**
 *
 * <p>2026-09-17 검증에서 드러난 구멍을 막는다: 운영에는 그 경로가 설정돼 있지 않은데
 * 기존 카탈로그 관리 테스트 세 개(`CatalogAdminApiTest`·`BackofficeApiTest`·`CombinedCatalogStoreTest`)가
 * **전부 경로를 넣고 돌아서**, 승인 시점에 500 이 나가는 것을 아무도 잡지 못했다.
 *
 * <p>여기서 검증하는 계약은 둘이다.
 * <ul>
 *   <li><b>조회는 된다</b> — 읽기는 클래스패스 합본 CSV 로 폴백한다. 되던 것을 깨지 않는다.
 *   <li><b>쓰기는 503</b> — 서버 오류(500)가 아니라 "지금은 편집할 수 없다"는 설정 문제로 답한다.
 *       특히 승인은 제안을 <b>FAILED 로 닫기 전에</b> 막아야 한다. 설정 문제로 남의 제안을 버리면
 *       나중에 경로를 채워도 그 제안은 되살릴 수 없다.
 * </ul>
 */
@SpringBootTest(properties = {
        "JWT_SECRET=VHlwZS1vbmx5LXRlc3Qta2V5LTMyaGFyYWN0ZXJzLW9yLW1vcmUh",
        "AUTH_SECURE_COOKIES=false", "AUTH_SESSION_COOKIE_NAME=YGB_SESSION",
        "CATALOG_ADMIN_USER_IDS=1",
        "ADMIN_ID=", "ADMIN_PASSWORD="})
@AutoConfigureMockMvc
@Testcontainers
class CatalogAdminDisabledTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    /** 운영과 같게 `yogobi.catalog.combined-csv` 를 **넣지 않는다.** 이것이 이 테스트의 전부다. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthTokens tokens;
    @Autowired CombinedCatalogStore store;

    private Cookie[] operator;

    /** 운영자는 `CATALOG_ADMIN_USER_IDS=1` 이므로 가장 먼저 만든 회원이어야 한다. */
    @BeforeEach
    void operatorIsTheFirstMember() {
        jdbc.update("DELETE FROM catalog_change_request");
        long id = jdbc.queryForList("SELECT id FROM app_user ORDER BY id LIMIT 1", Long.class).stream()
                .findFirst().orElseGet(() -> TestMembers.create(jdbc, "operator@example.com"));
        operator = TestMembers.session(tokens, id);
    }

    @Test void combinedCsvPathIsNotConfigured() {
        // 이 테스트의 전제. 깨지면 아래 단언들이 의미를 잃는다.
        assertThat(store.enabled()).isFalse();
    }

    @Test void readingTheCatalogStillWorksFromTheBundledCsv() throws Exception {
        send(get("/api/v1/admin/catalog"), operator)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));
        send(get("/api/v1/admin/catalog/subscription_service"), operator)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test void everyWriteProposalIsRefusedWithConfigurationErrorNotServerError() throws Exception {
        // 컨트롤러가 받는 본문은 평평한 맵이다(@RequestBody Map<String, String>).
        var body = JSON.writeValueAsBytes(Map.of("name", "새 서비스", "category", "OTT"));

        send(post("/api/v1/admin/catalog/subscription_service")
                .contentType(MediaType.APPLICATION_JSON).content(body), operator)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("YGB-CAT-503"));
        send(patch("/api/v1/admin/catalog/subscription_service/1")
                .contentType(MediaType.APPLICATION_JSON).content(body), operator)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("YGB-CAT-503"));
        send(delete("/api/v1/admin/catalog/subscription_service/1"), operator)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("YGB-CAT-503"));

        // 받지 않은 제안이 DB 에 남아서도 안 된다 — 승인할 수 없는 줄을 쌓아 두면 목록이 거짓말을 한다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_change_request", Integer.class)).isZero();
    }

    /** 설정 문제로 **남의 제안을 버리지 않는다.** 경로를 채우면 그대로 승인할 수 있어야 한다. */
    @Test void approvingIsRefusedAndLeavesTheProposalPending() throws Exception {
        long requestId = jdbc.queryForObject("""
                INSERT INTO catalog_change_request (proposer_id, action, dataset, row_key, payload, status)
                VALUES (1, 'UPDATE', 'subscription_service', '1', '{"name":"바뀐 이름"}', 'PENDING')
                RETURNING id""", Long.class);

        send(post("/api/v1/admin/catalog/requests/" + requestId + "/approve"), operator)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("YGB-CAT-503"));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM catalog_change_request WHERE id = ?", String.class, requestId))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT decided_by FROM catalog_change_request WHERE id = ?", Long.class, requestId)).isNull();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Cookie[] cookies) throws Exception {
        var session = new MockHttpSession();
        var issued = mvc.perform(get("/api/v1/auth/csrf").session(session).cookie(cookies))
                .andExpect(status().isOk()).andReturn();
        String token = JSON.readTree(issued.getResponse().getContentAsString())
                .path("data").path("token").asText();
        return mvc.perform(request.session(session).cookie(cookies).header("X-CSRF-TOKEN", token));
    }
}
