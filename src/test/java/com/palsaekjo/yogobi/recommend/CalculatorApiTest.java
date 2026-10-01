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

/** POST /api/v1/calculator — 특정 조합 총비용. 시드 번들(B4)로 G-04 최저경로를 종단 검증. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CalculatorApiTest {
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
        jdbc.execute("INSERT INTO carrier(id,name,carrier_type) VALUES (1,'SKT','MNO')");
        // P5형: 제휴 없음 33,333 (선택약정 절사 검증용)
        jdbc.execute("""
                INSERT INTO mobile_plan(id,carrier_id,name,network_type,base_price,data_mb,voice_min,sms_cnt,source_url,collected_at)
                VALUES (5,1,'절사검증플랜','FIVE_G',33333,100000,999999,9999,'http://seed','2026-09-08')""");
    }

    @Test
    void bundlePathBeatsIndividual_g04() throws Exception {
        // 티빙 스탠다드(8) + 웨이브 스탠다드(12): 개별 24,400 vs 번들 B4 15,000 → 33,333+15,000 = 48,333
        mvc.perform(post("/api/v1/calculator").contentType(MediaType.APPLICATION_JSON).content("""
                {"planId":5,"tierIds":[8,12],"optional":{"contractType":"NONE","hasFamilyBundle":false,
                 "currentCarrier":"SKT","networkType":"5G"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.monthlyTotal").value(48333))
                .andExpect(jsonPath("$.data.accuracy").value("FULL"))
                .andExpect(jsonPath("$.data.result.breakdown[?(@.label=='티빙x웨이브 더블 스탠다드')].amount")
                        .value(org.hamcrest.Matchers.hasItem(15000)));
    }

    @Test
    void selectiveContractFloors_g02c() throws Exception {
        // 33,333 x 0.75 = 24,999.75 → 내림 24,999 (구독 없이 통신비만)
        mvc.perform(post("/api/v1/calculator").contentType(MediaType.APPLICATION_JSON).content("""
                {"planId":5,"tierIds":[6],"optional":{"contractType":"SELECTIVE_25"}}"""))
                .andExpect(status().isOk())
                // 티빙 광고형(6) 5,500 추가: 24,999 + 5,500 = 30,499
                .andExpect(jsonPath("$.data.result.monthlyTotal").value(30499));
    }

    @Test
    void unknownPlan_returns404() throws Exception {
        mvc.perform(post("/api/v1/calculator").contentType(MediaType.APPLICATION_JSON).content("""
                {"planId":99999,"tierIds":[2],"optional":{}}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("YGB-CAT-001"))
                // G-77 b — 내부 ID 대신 다음 행동을 말한다
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("99999")),
                        org.hamcrest.Matchers.containsString("다시 골라"))));
    }

    @Test
    void missingTierIds_returns400() throws Exception {
        mvc.perform(post("/api/v1/calculator").contentType(MediaType.APPLICATION_JSON).content("""
                {"planId":5,"tierIds":[],"optional":{}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("YGB-REQ-001"))
                .andExpect(jsonPath("$.error.field").value("tierIds"));
    }

    /** G-86 c. 가족결합 할인을 반영하지 못하면 계산기도 그 사실을 말한다 — 추천 경로와 같은 안내. */
    @Test
    void familyDiscountLeftOutIsTold_g86c() throws Exception {
        // 통신사를 모른다 → 할인을 넣지 않았고, 어느 통신사인지 묻는다.
        mvc.perform(post("/api/v1/calculator").contentType(MediaType.APPLICATION_JSON).content("""
                {"planId":5,"tierIds":[6],"optional":{"contractType":"NONE","hasFamilyBundle":true,
                 "familyBundleDiscountKrw":11000}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.monthlyTotal").value(33333 + 5500))
                .andExpect(jsonPath("$.data.accuracy").value("PARTIAL"))
                .andExpect(jsonPath("$.data.missingInputs[?(@.field=='currentCarrier')].impact")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("11,000원은 이번 계산에 넣지 않았어요"))));
        // 다른 통신사 요금제를 골랐다 → 결합이 풀린다고 말한다.
        mvc.perform(post("/api/v1/calculator").contentType(MediaType.APPLICATION_JSON).content("""
                {"planId":5,"tierIds":[6],"optional":{"contractType":"NONE","hasFamilyBundle":true,
                 "familyBundleDiscountKrw":11000,"currentCarrier":"KT"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accuracy").value("PARTIAL"))
                .andExpect(jsonPath("$.data.missingInputs[?(@.field=='familyBundleDiscountKrw')].impact")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("KT 요금제에만 반영했어요"))));
    }

    /** G-92 c. 계산기도 알뜰폰 요금제에는 선택약정을 붙이지 않는다. */
    @Test
    void selectiveContractIsNotAppliedToMvnoPlans_g92c() throws Exception {
        jdbc.execute("INSERT INTO carrier(id,name,carrier_type) VALUES (9,'알뜰모바일','MVNO')");
        jdbc.execute("""
                INSERT INTO mobile_plan(id,carrier_id,name,network_type,base_price,data_mb,voice_min,sms_cnt,source_url,collected_at)
                VALUES (9,9,'알뜰5G','FIVE_G',20000,100000,999999,9999,'http://seed','2026-09-08')""");
        mvc.perform(post("/api/v1/calculator").contentType(MediaType.APPLICATION_JSON).content("""
                {"planId":9,"tierIds":[6],"optional":{"contractType":"SELECTIVE_25"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.monthlyTotal").value(20000 + 5500));
    }
}
