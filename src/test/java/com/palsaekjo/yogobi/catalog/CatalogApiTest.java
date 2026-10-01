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
// 로컬 .env(spring.config.import)가 CORS 오리진을 좁혀도 프리플라이트 단언은 여기서 고정한다.
@SpringBootTest(properties = {"yogobi.cors.allowed-origins=http://localhost:5173,http://localhost:3000,http://127.0.0.1:5173,https://yogob.fly.dev",
        "yogobi.catalog.memo-seconds=0"})   // 운영은 60초 기억. 여기서는 테스트마다 넣은 행을 바로 봐야 한다
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
    @Autowired CatalogReader reader;

    @Test
    void servicesReturnSeededServicesWithTiers() throws Exception {
        mvc.perform(get("/api/v1/catalog/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(seedRowCount("subscription_service")))
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

    /**
     * G-93 d. 가입 자격이 필요한 등급(청소년·학생 등)이 가장 싸도 <b>기본 등급</b>이 아니다. 화면은 첫 등급을 기본값으로 쓰고
     * 추천은 대표 등급으로 계산해, 자격 없는 사람의 금액이 그 등급으로 나왔다(지니뮤직 종량제를 뺀 뒤 청소년 등급이 1순위가 되는 경로).
     */
    @Test
    void eligibilityTiersAreNeitherTheDefaultNorTheRepresentative() throws Exception {
        long service = jdbc.queryForObject("""
                INSERT INTO subscription_service(name, category, official_url) VALUES ('자격검증음악','MUSIC','https://example.test')
                RETURNING id""", Long.class);
        jdbc.update("INSERT INTO subscription_tier(service_id,name,price,currency,tax_included) VALUES (?, '청소년 음악', 3000, 'KRW', TRUE)", service);
        jdbc.update("INSERT INTO subscription_tier(service_id,name,price,currency,tax_included) VALUES (?, '일반 음악', 8000, 'KRW', TRUE)", service);
        // 통신사 전용 상품(지니뮤직 'U+ 모바일 음악감상' 같은)도 그 통신사 가입자만 산다 — 기본값이 아니다.
        jdbc.update("INSERT INTO subscription_tier(service_id,name,price,currency,tax_included) VALUES (?, 'U+ 음악', 5000, 'KRW', TRUE)", service);
        try {
        mvc.perform(get("/api/v1/catalog/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id==" + service + ")].tiers[0].name").value(org.hamcrest.Matchers.hasItem("일반 음악")));
        var representative = reader.findRepresentativeTiers(java.util.List.of(service));
        org.assertj.core.api.Assertions.assertThat(representative).singleElement()
                .satisfies(t -> org.assertj.core.api.Assertions.assertThat(t.name()).contains("일반 음악"));
        } finally {   // 같은 DB 를 쓰는 다른 테스트가 서비스 수를 센다
            jdbc.update("DELETE FROM subscription_tier WHERE service_id = ?", service);
            jdbc.update("DELETE FROM subscription_service WHERE id = ?", service);
        }
    }

    /** 내장 합본 시드의 데이터셋 행수(헤더 제외). 건수를 박으면 시드가 늘 때마다 깨진다. */
    private static int seedRowCount(String dataset) throws java.io.IOException {
        var lines = CombinedCatalogCsv.bundled().get(dataset)
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank()).count();
        return (int) lines - 1;
    }
}
