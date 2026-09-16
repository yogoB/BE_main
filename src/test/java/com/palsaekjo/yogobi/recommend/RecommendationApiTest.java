package com.palsaekjo.yogobi.recommend;

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
 * POST /api/v1/recommendations 종단 검증. 핵심은 G-01: 요금제 쌍은 그대로 두고 원하는 서비스만
 * 바꾸면 순위가 뒤집혀야 한다 ("미사용 혜택은 0원").
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RecommendationApiTest {
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

    @BeforeEach
    void fixtures() {
        jdbc.execute("TRUNCATE catalog_candidate");
        jdbc.execute("DELETE FROM plan_benefit");
        jdbc.execute("DELETE FROM mobile_plan");
        jdbc.execute("DELETE FROM carrier");
        jdbc.execute("INSERT INTO carrier(id,name,carrier_type) VALUES (1,'SKT','MNO'),(2,'KT','MNO')");
        // P1형: 넷플릭스 무료 55,000 / P2형: 웨이브 무료 45,000 (docs/testing.md G-01)
        jdbc.execute("""
                INSERT INTO mobile_plan(id,carrier_id,name,network_type,base_price,data_mb,voice_min,sms_cnt,source_url,collected_at)
                VALUES (1,1,'넷플플랜','FIVE_G',55000,100000,999999,9999,'http://seed','2026-09-08'),
                       (2,2,'웨이브플랜','FIVE_G',45000,100000,999999,9999,'http://seed','2026-09-08')""");
        jdbc.execute("""
                INSERT INTO plan_benefit(mobile_plan_id,service_id,tier_id,benefit_type,is_exclusive,source_url,collected_at)
                VALUES (1,1,NULL,'FREE',false,'http://seed','2026-09-08'),
                       (2,4,NULL,'FREE',false,'http://seed','2026-09-08')""");
    }

    @Test
    void wantingNetflix_netflixPlanWins() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},"optional":{"contractType":"NONE"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].planName").value("넷플플랜"))
                .andExpect(jsonPath("$.data.results[0].monthlyTotal").value(55000))
                .andExpect(jsonPath("$.data.results[1].planName").value("웨이브플랜"))
                .andExpect(jsonPath("$.data.results[1].monthlyTotal").value(58500));
    }

    @Test
    void wantingWave_rankReverses() throws Exception {
        // 요금제 쌍은 그대로, 원하는 서비스만 웨이브로 → 순위 역전
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[4]},"optional":{"contractType":"NONE"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].planName").value("웨이브플랜"))
                .andExpect(jsonPath("$.data.results[0].monthlyTotal").value(45000))
                .andExpect(jsonPath("$.data.results[1].planName").value("넷플플랜"))
                .andExpect(jsonPath("$.data.results[1].monthlyTotal").value(65900));
    }

    @Test
    void missingHasFamilyBundle_isPartialWithMissingInput() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                 "optional":{"currentCarrier":"SKT","networkType":"5G","contractType":"SELECTIVE_25"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accuracy").value("PARTIAL"))
                .andExpect(jsonPath("$.data.missingInputs[*].field").value(
                        org.hamcrest.Matchers.hasItem("hasFamilyBundle")));
    }

    @Test
    void allOptionalsPresent_isFull() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                 "optional":{"currentCarrier":"SKT","networkType":"5G","contractType":"NONE","hasFamilyBundle":false}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accuracy").value("FULL"))
                .andExpect(jsonPath("$.data.missingInputs").isEmpty());
    }

    @Test
    void missingRequired_returns400() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[]},"optional":{}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("YGB-REQ-001"))
                .andExpect(jsonPath("$.error.field").value("wantedServiceIds"));
    }

    // --- G-12 카탈로그 결손은 막지 않는다 (D-17) ---

    @Test
    void g12a_unknownServiceIsExcludedNotRejected() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1,99]},"optional":{"contractType":"NONE"}}"""))
                .andExpect(status().isOk())                                   // 이전엔 400 YGB-REQ-001
                .andExpect(jsonPath("$.data.accuracy").value("PARTIAL"))
                .andExpect(jsonPath("$.data.results[0].planName").value("넷플플랜"))   // 아는 서비스(1)로 계산
                .andExpect(jsonPath("$.data.missingInputs[*].field").value(
                        org.hamcrest.Matchers.hasItem("wantedServiceIds")));
        assertGap("SUBSCRIPTION_TIER", "serviceId:99", 1);
    }

    @Test
    void g12b_allServicesUnknownStillReturnsResults() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[99]},"optional":{"contractType":"NONE"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(2))      // 혜택 0원, 기본료로 계산
                .andExpect(jsonPath("$.data.results[0].planName").value("웨이브플랜"))  // 45,000 이 더 싸다
                .andExpect(jsonPath("$.data.results[0].monthlyTotal").value(45000));
        assertGap("SUBSCRIPTION_TIER", "serviceId:99", 1);
    }

    @Test
    void g12cd_noCandidatePlanReturnsEmptyResultsAndCountsRepeats() throws Exception {
        String body = """
                {"required":{"monthlyDataGb":200,"wantedServiceIds":[1]},"optional":{}}""";
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())                                   // 이전엔 422 YGB-CAL-001
                .andExpect(jsonPath("$.data.results").isEmpty())
                .andExpect(jsonPath("$.data.accuracy").value("PARTIAL"))
                .andExpect(jsonPath("$.data.missingInputs[*].field").value(
                        org.hamcrest.Matchers.hasItem("monthlyDataGb")));
        assertGap("MOBILE_PLAN", "dataMb>=204800,network=ANY", 1);

        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertGap("MOBILE_PLAN", "dataMb>=204800,network=ANY", 2);   // 행은 안 늘고 횟수만 오른다
    }

    @Test
    void g12e_recordingStopsAtRowCapButRequestStillSucceeds() throws Exception {
        jdbc.update("""
                INSERT INTO catalog_candidate(kind, query_text, status)
                SELECT 'MOBILE_PLAN', 'filler-' || g, 'REQUESTED' FROM generate_series(1, 10000) g""");

        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1,99]},"optional":{"contractType":"NONE"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].planName").value("넷플플랜"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_candidate", Integer.class)).isEqualTo(10000);
    }

    private void assertGap(String kind, String queryText, int expectedCount) {
        assertThat(jdbc.queryForObject(
                "SELECT requested_cnt FROM catalog_candidate WHERE kind=? AND query_text=?",
                Integer.class, kind, queryText)).isEqualTo(expectedCount);
    }
}
