package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 결손 기록 공개 경로(D-56, G-42). 비회원·CSRF 없이 200 이고, <b>응답은 언제나 같다</b> —
 * 그 이름이 카탈로그에 있는지 없는지 알려주지 않는다.
 */
@SpringBootTest(properties = {"yogobi.catalog.gap-limit=5"})   // 운영 기본은 60. 6번째를 보려고 내린다
@AutoConfigureMockMvc
@Testcontainers
class CatalogGapApiTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE catalog_candidate");
        jdbc.execute("DELETE FROM auth_rate_limit");
    }

    /** G-42 a·b — 비회원이 CSRF 없이 남길 수 있고, 같은 이름은 행이 늘지 않고 횟수만 오른다. */
    @Test
    void anyoneCanRecordAGapAndRepeatsOnlyBumpTheCount() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/catalog/gaps").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"kind\":\"MOBILE_PLAN\",\"queryText\":\"SKT 청년 59\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.recorded").value(true));
        }
        assertThat(jdbc.queryForMap("SELECT kind, query_text, status, requested_cnt FROM catalog_candidate"))
                .containsEntry("kind", "MOBILE_PLAN").containsEntry("query_text", "SKT 청년 59")
                .containsEntry("status", "REQUESTED").containsEntry("requested_cnt", 2);
    }

    /** G-42 c — 카탈로그에 <b>있는</b> 이름을 보내도 응답이 같다. 존재 여부를 알려주는 통로가 아니다. */
    @Test
    void theAnswerIsTheSameForNamesThatExist() throws Exception {
        String existing = jdbc.queryForObject("SELECT name FROM mobile_plan WHERE active ORDER BY id LIMIT 1", String.class);
        mvc.perform(post("/api/v1/catalog/gaps").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"MOBILE_PLAN\",\"queryText\":\"" + existing + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recorded").value(true))
                .andExpect(jsonPath("$.data.length()").value(1));   // recorded 하나뿐 — 다른 정보는 없다
    }

    /** G-42 d — 빈 이름·200자 초과·모르는 종류는 400. 쓰레기가 수집 목록에 쌓이지 않게 한다. */
    @Test
    void badInputsAreRejected() throws Exception {
        for (String body : new String[] {
                "{\"kind\":\"MOBILE_PLAN\",\"queryText\":\"   \"}",
                "{\"kind\":\"MOBILE_PLAN\",\"queryText\":\"" + "가".repeat(201) + "\"}",
                "{\"kind\":\"CARRIER\",\"queryText\":\"SKT\"}"}) {
            mvc.perform(post("/api/v1/catalog/gaps").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_candidate", Integer.class)).isZero();
    }

    /** G-42 e — 공개 쓰기 경로라 발신지당 상한이 있다. 표를 쓰레기로 채우지 못하게 한다. */
    @Test
    void oneSourceCannotFloodTheTable() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/catalog/gaps").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"kind\":\"MOBILE_PLAN\",\"queryText\":\"이름 " + i + "\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/catalog/gaps").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"MOBILE_PLAN\",\"queryText\":\"이름 5\"}"))
                .andExpect(status().isTooManyRequests());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_candidate", Integer.class)).isEqualTo(5);
    }
}
