package com.palsaekjo.yogobi.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 카탈로그 GET API 검증. 서비스/티어는 시드로 실동작, 요금제는 시드 전이라 fixture 로 확인. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CatalogApiTest {
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
    void servicesReturnSeededServicesWithTiers() throws Exception {
        mvc.perform(get("/api/v1/catalog/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[0].name").value("넷플릭스"))
                .andExpect(jsonPath("$.data[0].tiers[?(@.name=='스탠다드')].price").value(
                        org.hamcrest.Matchers.hasItem(13500)));
    }

    @Test
    void corsPreflightAllowsLocalFrontendOrigin() throws Exception {
        mvc.perform(options("/api/v1/catalog/services")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void benefitsForUnknownPlanReturn404() throws Exception {
        mvc.perform(get("/api/v1/catalog/plans/99999/benefits"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("YGB-CAT-001"));
    }

    @Test
    void plansAndBenefitsReflectSeededRows() throws Exception {
        jdbc.execute("DELETE FROM plan_benefit");
        jdbc.execute("DELETE FROM mobile_plan");
        jdbc.execute("DELETE FROM carrier");
        jdbc.execute("INSERT INTO carrier(id,name,carrier_type) VALUES (1,'SKT','MNO')");
        jdbc.execute("""
                INSERT INTO mobile_plan(id,carrier_id,name,network_type,base_price,data_mb,voice_min,sms_cnt,source_url,collected_at)
                VALUES (1,1,'5G 슬림','FIVE_G',55000,100000,999999,9999,'http://seed','2026-09-08')""");
        jdbc.execute("""
                INSERT INTO plan_benefit(mobile_plan_id,service_id,tier_id,benefit_type,is_exclusive,source_url,collected_at)
                VALUES (1,1,2,'FREE',false,'http://seed','2026-09-08')""");

        mvc.perform(get("/api/v1/catalog/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].carrier").value("SKT"))
                .andExpect(jsonPath("$.data[0].name").value("5G 슬림"));

        mvc.perform(get("/api/v1/catalog/plans/1/benefits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].serviceName").value("넷플릭스"))
                .andExpect(jsonPath("$.data[0].benefitType").value("FREE"));
    }
}
