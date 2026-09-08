package com.palsaekjo.yogobi.recommend;

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

    @Test
    void noCandidatePlan_returns422() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":200,"wantedServiceIds":[1]},"optional":{}}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("YGB-CAL-001"));
    }
}
