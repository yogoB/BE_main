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
                       (2,2,'웨이브플랜','FIVE_G',45000,100000,999999,9999,'http://seed','2026-09-08'),
                       (3,1,'작은플랜','LTE',20000,1000,100,100,'http://seed','2026-09-08')""");
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

    /**
     * G-52 — <b>후보에서 빠진 이유를 서버가 말한다</b>(D-61). 화면이 필터 규칙을 거울처럼 들고 있다가
     * 실제로 틀렸다: 데이터가 요구보다 <b>많은데</b> "데이터가 모자라다"고 적혔다(운영, 2026-09-20).
     * 어느 조건에서 걸렸는지는 후보를 거른 쪽만 확실히 안다.
     *
     * <p>판정은 후보 질의의 WHERE 절을 <b>그대로 한 행에 적용해</b> 만든다 — 자바로 옮겨 적으면
     * 거울이 하나 더 생길 뿐이다({@code CatalogReader.currentPlanExclusion}).
     */
    @Test
    void tellsWhyTheCurrentPlanIsNotACandidate() throws Exception {
        // 작은플랜 1GB · LTE. 5GB 를 요구하면 데이터에서 먼저 걸린다.
        var data = recommendWithCurrentPlan(3).path("current").path("excluded");
        assertThat(data.path("reason").asText()).isEqualTo("DATA");
        assertThat(data.path("planDataMb").asLong()).isEqualTo(1000);
        assertThat(data.path("requiredDataMb").asLong()).isEqualTo(5120);

        // 데이터는 넉넉한데 망이 어긋나는 경우 — 여기서 화면 판정이 틀렸었다.
        // 이 테스트 안에서만 LTE 후보를 하나 넣는다(@BeforeEach 가 매번 지운다). 후보가 0건이면
        // 서비스가 "조건을 만족하는 요금제가 없어요" 로 먼저 끝나 current 자체가 안 실린다.
        jdbc.execute("""
                INSERT INTO mobile_plan(id,carrier_id,name,network_type,base_price,data_mb,voice_min,sms_cnt,source_url,collected_at)
                VALUES (4,2,'LTE넉넉','LTE',30000,20000,300,300,'http://seed','2026-09-08')""");
        String body = mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":5,"wantedServiceIds":[1]},
                 "optional":{"currentPlanId":1,"networkType":"LTE","contractType":"NONE"}}"""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var network = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
                .path("data").path("current").path("excluded");
        assertThat(network.path("reason").asText()).isEqualTo("NETWORK");
        assertThat(network.path("planNetwork").asText()).isEqualTo("FIVE_G");
        assertThat(network.path("requiredNetwork").asText()).isEqualTo("LTE");
        assertThat(network.path("planDataMb").asLong()).isEqualTo(100000);   // 데이터는 남는다

        // 후보인 요금제에는 이유가 없다 — 할 말이 없을 때는 아무 말도 하지 않는다.
        assertThat(recommendWithCurrentPlan(1).path("current").path("excluded").isNull()).isTrue();
    }

    /**
     * G-51 — <b>'변경 최소'가 '지금'보다 비싸면, 지금 요금제가 요구 조건을 통과하지 못한 것이다.</b>
     *
     * <p>후보 질의의 조건은 {@code active · data_mb >= 요구량 · 망 · 가입자격} 넷뿐이고 현재 요금제를
     * 따로 빼지 않는다. 그러니 지금 요금제가 조건을 통과하면 그것도 후보이고, 같은 통신사에서 가장 싼 것을
     * 고르는 {@code minimalChange} 는 <b>지금보다 비쌀 수 없다.</b> 비싸게 나왔다면 통과하지 못한 것이다.
     *
     * <p>운영에서 실제로 본 모양이다(2026-09-20 페르소나 B): LG U+ `LTE 표준`(데이터 0MB) 사용자가
     * 5GB 를 원하면 '지금'보다 '변경 최소'가 비싸게 나온다. 화면이 그 이유를 적으려면 이 성질이 참이어야
     * 하므로 여기서 고정한다 — 리팩터링이 후보 질의나 선택 규칙을 건드리면 여기서 먼저 걸린다.
     *
     * <p>고정 픽스처로만 본다: SKT `작은플랜`(1GB) · SKT `넷플플랜`(100GB, 넷플릭스 무료).
     * 넷플릭스를 원하면서 5GB 를 요구하면 `작은플랜` 은 후보에서 빠진다.
     */
    @Test
    void minimalChangeCostsMoreOnlyWhenTheCurrentPlanFailsTheRequirement() throws Exception {
        // 지금 = 작은플랜(1GB). 5GB 요구를 통과하지 못한다 → 지금(20,000+13,500)보다 비싼 넷플플랜(55,000)이 나온다.
        var failing = recommendWithCurrentPlan(3);
        long now = failing.path("current").path("cost").path("monthlyTotal").asLong();
        long minimal = failing.path("minimalChange").path("monthlyTotal").asLong();
        assertThat(now).isEqualTo(33500);
        assertThat(minimal).isEqualTo(55000).isGreaterThan(now);
        assertThat(failing.path("minimalChange").path("planId").asLong()).isNotEqualTo(3);

        // 뒤집으면: 조건을 통과하는 요금제를 쓰고 있으면 '변경 최소'는 지금보다 비쌀 수 없다.
        var passing = recommendWithCurrentPlan(1);
        assertThat(passing.path("minimalChange").path("monthlyTotal").asLong())
                .isLessThanOrEqualTo(passing.path("current").path("cost").path("monthlyTotal").asLong());
    }

    private com.fasterxml.jackson.databind.JsonNode recommendWithCurrentPlan(long planId) throws Exception {
        String body = mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":5,"wantedServiceIds":[1]},
                 "optional":{"currentPlanId":%d,"contractType":"NONE"}}""".formatted(planId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minimalChange").exists())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data");
    }

    /**
     * G-50 — 공개 경로의 목록 길이 상한. 인증도 CSRF 도 없는 자리라, 길이를 안 보면 id 를 만 개 실은
     * 한 요청이 그대로 {@code IN (...)} 파라미터 만 개가 된다. 카탈로그 전체가 서비스 36개라
     * 이 상한(200)은 정상 사용자에게 닿지 않는다.
     */
    @Test
    void rejectsAbsurdlyLongIdLists() throws Exception {
        String ids = java.util.stream.IntStream.rangeClosed(1, 201)
                .mapToObj(Integer::toString).collect(java.util.stream.Collectors.joining(","));
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[%s]},"optional":{}}""".formatted(ids)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("wantedServiceIds"));
        // 상한 안쪽은 그대로 통과한다 — 없는 서비스 id 는 막지 않고 안내로 돌려주는 규칙(G-12)도 그대로다.
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1,2,3]},"optional":{}}"""))
                .andExpect(status().isOk());
    }

    /**
     * 등급을 고르면 그 등급으로 계산한다. 지정 전에는 언제나 대표 등급(스탠다드 13,500)이라
     * 프리미엄 가입자에게도 스탠다드 금액을 보여주고 있었다.
     * 넷플릭스 프리미엄 17,000 → 웨이브플랜 45,000 + 17,000 = 62,000 (대표 등급이면 58,500).
     */
    @Test
    void pickedTierIsUsedInsteadOfTheRepresentativeOne() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1],"wantedTierIds":[3]},
                 "optional":{"contractType":"NONE"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].monthlyTotal").value(55000))
                .andExpect(jsonPath("$.data.results[1].monthlyTotal").value(62000));
    }

    /** 고르지 않은 서비스의 등급(웨이브 프리미엄 13)은 버린다 — 남의 등급으로 금액을 만들지 않는다. */
    @Test
    void tierOfAnUnwantedServiceIsIgnored() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1],"wantedTierIds":[13]},
                 "optional":{"contractType":"NONE"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[1].monthlyTotal").value(58500));
    }

    /** 등급을 안 보내면 예전과 똑같다 — 이 필드를 모르는 호출(챗봇 포함)이 그대로 동작해야 한다. */
    @Test
    void omittingTierIdsKeepsTheOldBehaviour() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1],"wantedTierIds":[]},
                 "optional":{"contractType":"NONE"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[1].monthlyTotal").value(58500));
    }

    /** G-28 g — 음수 할인액은 400. 요금을 올리는 "할인"은 입력 실수다. */
    @Test
    void g28g_negativeFamilyDiscountIsRejected() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                 "optional":{"contractType":"NONE","hasFamilyBundle":true,"familyBundleDiscountKrw":-1}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("familyBundleDiscountKrw"));
    }

    /** G-28 b — 결합 중인데 할인액을 모르면 그 금액을 묻는 안내가 남는다. */
    @Test
    void g28b_bundledWithoutAmountAsksForIt() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                 "optional":{"contractType":"NONE","hasFamilyBundle":true}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.missingInputs[?(@.field=='familyBundleDiscountKrw')]").exists());
    }

    /**
     * QA 피드백(2026-09-17): 목록에 없는 통신사를 직접 적을 수 있게 하되 그게 수집 신호가 돼야 한다.
     * 지금까지 currentCarrier 는 받아만 두고 아무 데도 쓰지 않았다 — 결손으로 남긴다.
     */
    @Test
    void unknownCarrierIsRecordedAsACatalogGap() throws Exception {
        recommendWithCarrier("듣도보도못한모바일").andExpect(status().isOk());
        recommendWithCarrier("듣도보도못한모바일").andExpect(status().isOk());

        var row = jdbc.queryForMap(
                "SELECT kind, requested_cnt FROM catalog_candidate WHERE query_text = ?", "carrier:듣도보도못한모바일");
        assertThat(row.get("kind")).isEqualTo("MOBILE_PLAN");
        // 같은 이름이 또 오면 세기만 한다 — 수집 우선순위가 된다.
        assertThat(((Number) row.get("requested_cnt")).intValue()).isEqualTo(2);
    }

    /** 아는 통신사는 결손이 아니다. 공백·대소문자가 달라도 같은 것으로 본다. */
    @Test
    void knownCarrierIsNotRecorded() throws Exception {
        recommendWithCarrier("s k t").andExpect(status().isOk());
        recommendWithCarrier("SKT").andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM catalog_candidate WHERE query_text LIKE 'carrier:%'", Integer.class)).isZero();
    }

    /** '알뜰폰' 은 통신사 이름이 아니라 분류다. 옛 화면이 보내던 값이라 결손으로 세지 않는다. */
    @Test
    void genericCarrierLabelIsNotRecorded() throws Exception {
        recommendWithCarrier("알뜰폰").andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM catalog_candidate WHERE query_text LIKE 'carrier:%'", Integer.class)).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions recommendWithCarrier(String carrier) throws Exception {
        return mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                 "optional":{"contractType":"NONE","currentCarrier":"%s"}}""".formatted(carrier)));
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

    /* ── G-29. 가족결합 할인은 지금 통신사에서만 유지된다 ────────────────────────────
       운영에서 알뜰폰 요금제에 "가족결합 할인 −8,990원"이 찍혔다. 옮기는 순간 사라질 할인을
       빼고 1등으로 올린 것이라, 사용자는 갈아탄 뒤에 그만큼 더 낸다. */

    /** G-29 a — 지금 통신사(SKT) 요금제에만 붙는다. 다른 통신사 후보는 그대로다. */
    @Test
    void g29a_familyBundleAppliesOnlyToTheCurrentCarrier() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                 "optional":{"contractType":"NONE","currentCarrier":"SKT",
                             "hasFamilyBundle":true,"familyBundleDiscountKrw":11000}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].planName").value("넷플플랜"))
                .andExpect(jsonPath("$.data.results[0].monthlyTotal").value(44000))
                .andExpect(jsonPath("$.data.results[1].planName").value("웨이브플랜"))
                .andExpect(jsonPath("$.data.results[1].monthlyTotal").value(58500));
    }

    /**
     * G-29 b — <b>핵심</b>. 결합 할인은 순위를 바꾼다. 모든 후보에서 똑같이 빼면 순위가 안 바뀌므로
     * 버그가 보이지 않는다. 현재 통신사가 KT 면 45,000 − 11,000 = 47,500 이 1등이 돼야 한다.
     */
    @Test
    void g29b_bundleDiscountFlipsTheRanking() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                 "optional":{"contractType":"NONE","currentCarrier":"KT",
                             "hasFamilyBundle":true,"familyBundleDiscountKrw":11000}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].planName").value("웨이브플랜"))
                .andExpect(jsonPath("$.data.results[0].monthlyTotal").value(47500))
                .andExpect(jsonPath("$.data.results[1].monthlyTotal").value(55000));
    }

    /** G-29 c — 어느 통신사에 붙은 할인인지 모르면 어디에도 적용하지 않는다. 그 사실을 금액과 함께 말한다. */
    @Test
    void g29c_unknownCarrierMeansTheBundleIsNotApplied() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                 "optional":{"contractType":"NONE","hasFamilyBundle":true,"familyBundleDiscountKrw":11000}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].monthlyTotal").value(55000))
                .andExpect(jsonPath("$.data.results[1].monthlyTotal").value(58500))
                .andExpect(jsonPath("$.data.missingInputs[?(@.field=='currentCarrier')].impact")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("11,000"))));
    }

    /** G-29 c — "알뜰폰"은 통신사가 아니라 분류다. 지목하지 못하는 값이면 모르는 것과 같게 다룬다. */
    @Test
    void g29c_genericCarrierCountsAsUnknown() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                 "optional":{"contractType":"NONE","currentCarrier":"알뜰폰",
                             "hasFamilyBundle":true,"familyBundleDiscountKrw":11000}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].monthlyTotal").value(55000))
                .andExpect(jsonPath("$.data.missingInputs[?(@.field=='currentCarrier')].impact")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("11,000"))));
    }

    /** G-29 d — 이름 비교는 공백·대소문자를 무시한다("KT 엠모바일" = "KT엠모바일"). */
    @Test
    void g29d_carrierNameIgnoresSpacingAndCase() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                 "optional":{"contractType":"NONE","currentCarrier":"s kt ",
                             "hasFamilyBundle":true,"familyBundleDiscountKrw":11000}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].monthlyTotal").value(44000));
    }

    /** G-29 e — 다른 통신사 후보가 결과에 섞여 있으면 "옮기면 결합이 풀린다"고 말한다. */
    @Test
    void g29e_noticeSaysTheBundleIsLostWhenSwitching() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                 "optional":{"contractType":"NONE","currentCarrier":"SKT",
                             "hasFamilyBundle":true,"familyBundleDiscountKrw":11000}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.missingInputs[?(@.field=='familyBundleDiscountKrw')]").exists());
    }

    /** G-29 f — 요금제를 골랐다면 그 통신사가 현재 통신사다. 이름을 안 적어도 a 와 같은 결과가 나온다. */
    @Test
    void g29f_currentPlanIdDecidesTheCarrier() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                 "optional":{"contractType":"NONE","currentPlanId":1,
                             "hasFamilyBundle":true,"familyBundleDiscountKrw":11000}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].monthlyTotal").value(44000))
                .andExpect(jsonPath("$.data.results[1].monthlyTotal").value(58500));
    }

    /* ── G-30. 현재 요금제를 알려주면 같은 기준으로 계산한다 ───────────────────────── */

    /** G-30 a·b — 현재 요금제 + 같은 구독을 후보와 같은 규칙으로 계산하고, 1순위 대비 절감액을 낸다. */
    @Test
    void g30ab_currentPlanCostAndSavings() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                 "optional":{"contractType":"NONE","currentPlanId":2}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current.cost.planName").value("웨이브플랜"))
                .andExpect(jsonPath("$.data.current.cost.monthlyTotal").value(58500))  // 45,000 + 넷플릭스 13,500
                .andExpect(jsonPath("$.data.current.monthlySavings").value(3500))      // 58,500 − 55,000
                .andExpect(jsonPath("$.data.current.annualSavings").value(42000));
    }

    /** G-30 c — 안 보내면 없다. 화면은 예전처럼 사용자가 적은 값을 '현재' 열에 둔다. */
    @Test
    void g30c_noCurrentPlanIdMeansNoCurrentBlock() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},"optional":{"contractType":"NONE"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").doesNotExist());
    }

    /** G-30 d — 없는 요금제 id 로 추천 전체를 버리지 않는다. 안내 한 줄로 끝낸다(원칙 5-①). */
    @Test
    void g30d_unknownCurrentPlanIdIsANoticeNotAnError() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                 "optional":{"contractType":"NONE","currentPlanId":9999}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].monthlyTotal").value(55000))
                .andExpect(jsonPath("$.data.current").doesNotExist())
                .andExpect(jsonPath("$.data.missingInputs[?(@.field=='currentPlanId')]").exists());
    }

    /** G-30 e — 지금이 더 싸면 음수 그대로. 20GB 를 쓰려면 더 내야 한다는 사실을 감추지 않는다. */
    @Test
    void g30e_savingsCanBeNegative() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                 "optional":{"contractType":"NONE","currentPlanId":3}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current.cost.monthlyTotal").value(33500))  // 20,000 + 13,500
                .andExpect(jsonPath("$.data.current.monthlySavings").value(-21500));
    }

    /** D-50 — /narrate 는 추천과 같은 공개 경로다. 비회원·CSRF 토큰 없이 200, 설명 모양(message·reasons·notices)으로 온다. */
    @Test
    void narrateIsPublicAndCsrfExemptLikeRecommendations() throws Exception {
        mvc.perform(post("/api/v1/recommendations/narrate").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},"optional":{"contractType":"NONE"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reasons").isArray())
                .andExpect(jsonPath("$.data.notices").isArray());
        // 추천 본체는 이제 설명을 싣지 않는다.
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},"optional":{"contractType":"NONE"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].planName").value("넷플플랜"))
                .andExpect(jsonPath("$.data.message").doesNotExist())
                .andExpect(jsonPath("$.data.reasons").isEmpty());
    }

    /**
     * G-41 — '변경 최소'는 번호이동 없이 요금제만 바꾸는 선택지다(D-55). 웨이브를 원하면 전체 1순위는
     * KT 웨이브플랜(45,000)이지만, SKT 사용자에게는 SKT 안에서 가장 싼 넷플플랜(55,000 + 웨이브 10,900)이
     * '변경 최소'다. 운영에서 두 열이 같은 알뜰폰으로 나와 열을 나눈 뜻이 사라졌던 것을 고친다.
     */
    @Test
    void g41_minimalChangeStaysWithTheCurrentCarrier() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[4]},
                 "optional":{"contractType":"NONE","currentCarrier":"SKT"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].planName").value("웨이브플랜"))     // 전체 1순위는 KT
                .andExpect(jsonPath("$.data.results[0].monthlyTotal").value(45000))
                .andExpect(jsonPath("$.data.minimalChange.planName").value("넷플플랜"))    // 번호이동 없이면 SKT
                .andExpect(jsonPath("$.data.minimalChange.carrier").value("SKT"))
                .andExpect(jsonPath("$.data.minimalChange.monthlyTotal").value(65900));
    }

    /** G-41 b — 지금 통신사가 이미 가장 싸면 '변경 최소'와 1순위가 같다. 그것도 답이다. */
    @Test
    void g41b_minimalChangeCanEqualTheCheapest() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[4]},
                 "optional":{"contractType":"NONE","currentCarrier":"KT"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minimalChange.planName").value("웨이브플랜"))
                .andExpect(jsonPath("$.data.minimalChange.monthlyTotal").value(45000));
    }

    /** G-41 c — 현재 통신사를 모르면 null 이다. 어느 통신사에 머무는 것인지 모르면 '변경 최소'도 없다. */
    @Test
    void g41c_withoutACarrierThereIsNoMinimalChange() throws Exception {
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[4]},"optional":{"contractType":"NONE"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minimalChange").doesNotExist());
    }

    /**
     * G-45 — 통합요금제(D-58)는 5G·LTE 어느 쪽을 골라도 후보다. KT 현재 라인업이 "5G/LTE 구분없이" 라
     * 5G 로만 적어 두던 동안 <b>LTE 를 고른 사용자에게 KT 후보가 0건</b>이었다.
     */
    @Test
    void g45_unifiedPlansAnswerBothNetworks() throws Exception {
        jdbc.execute("""
                INSERT INTO mobile_plan(id,carrier_id,name,network_type,base_price,data_mb,voice_min,sms_cnt,source_url,collected_at)
                VALUES (4,2,'통합베이직','LTE_5G',33000,100000,999999,9999,'http://seed','2026-09-20')""");
        for (String network : new String[] {"5G", "LTE"}) {
            mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                    {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                     "optional":{"contractType":"NONE","networkType":"%s"}}""".formatted(network)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.results[*].planName")
                            .value(org.hamcrest.Matchers.hasItem("통합베이직")));
        }
        // 3G 를 고른 사람에게는 넣지 않는다 — 그 요금제는 3G 단말에서 쓰는 것이 아니다.
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                 "optional":{"contractType":"NONE","networkType":"3G"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results").isEmpty());
        jdbc.execute("DELETE FROM mobile_plan WHERE id = 4");
    }

    /**
     * G-64. 망을 좁혀서 <b>더 싼 요금제를 놓쳤으면 그 사실을 말한다.</b>
     *
     * <p>실제 사용자 사례에서 나왔다(2026-09-21). 디테일 모드는 "<b>사용 중인</b> 통신망"을 묻고
     * 그 답을 후보 필터로 쓴다. 무제한을 원한 사용자가 5G라고 사실대로 답했더니 알뜰폰 LTE
     * 무제한이 통째로 빠져 <b>"지금이 더 싸요"</b>가 나왔다 — 망을 안 좁히면 절감이 나오는
     * 경우였다. <b>"지금 5G를 쓴다"와 "5G만 원한다"는 다른 말인데</b> 화면 어디에도 그 사실이 없었다.
     *
     * <p>필터는 그대로 둔다(사용자가 고른 조건이다). 대신 그 선택이 무엇을 지웠는지 숫자로 말한다.
     * <b>총액은 약속하지 않는다</b> — 비교는 기본료끼리이고 제휴 혜택은 후보마다 달라 뒤집힐 수 있다.
     */
    @Test
    void g64_narrowingTheNetworkSaysWhatItCost() throws Exception {
        // LTE 전용 알뜰폰이 5G 후보보다 싸다 — 실제 카탈로그의 모양(무제한 LTE 46,200 vs 5G 55,000)과 같다.
        // 금액을 시드 전체보다 확실히 낮게 잡는다: 정확한 차액은 카탈로그가 자라면 바뀌므로 못박지 않는다.
        jdbc.execute("""
                INSERT INTO mobile_plan(id,carrier_id,name,network_type,base_price,data_mb,voice_min,sms_cnt,source_url,collected_at)
                VALUES (5,2,'싼LTE무제한','LTE',1000,999999,999999,9999,'http://seed','2026-09-21')""");
        try {
            // a — 5G 로 좁히면 LTE 를 뺐다는 것과 그 차액을 말한다. 내부 enum(FIVE_G)이 아니라 "5G" 로 적는다.
            mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                    {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                     "optional":{"contractType":"NONE","networkType":"5G"}}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.missingInputs[?(@.field=='networkType')].impact")
                            .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.allOf(
                                    org.hamcrest.Matchers.containsString("5G 로 좁혀서"),
                                    // 내부 enum 이 사용자 문구로 새어 나가면 안 된다 — 처음에 "FIVE_G 로 좁혀서" 가 나갔다.
                                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("FIVE_G")),
                                    // 남은 최저 45,000(웨이브플랜) - 뺀 최저 1,000 = 44,000
                                    org.hamcrest.Matchers.containsString("44,000원 더 싼 것도 있어요")))));

            // b — 안 좁혔으면 놓친 것도 없다. 없는 손해를 안내하면 소음이다.
            //     (망을 안 고르면 "지정하면 더 정확해져요" 안내는 원래 나간다 — 그건 다른 말이다.)
            mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                    {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                     "optional":{"contractType":"NONE"}}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.missingInputs[?(@.field=='networkType')].impact")
                            .value(org.hamcrest.Matchers.everyItem(
                                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("뺐어요")))));

            // c — 좁혔는데 뺀 쪽이 더 비싸면 말하지 않는다. 손해가 아니기 때문이다.
            mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                    {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},
                     "optional":{"contractType":"NONE","networkType":"LTE"}}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.missingInputs[?(@.field=='networkType')].impact")
                            .value(org.hamcrest.Matchers.everyItem(
                                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("뺐어요")))));
        } finally {
            jdbc.execute("DELETE FROM mobile_plan WHERE id = 5");
        }
    }

    /**
     * G-66. 기간 한정 특가는 <b>모르면 모른다고 답한다</b>(2026-09-21, 사용자 지시).
     *
     * <p>알뜰폰은 3·6·7개월 특가가 흔한데 카탈로그에 그 기간을 담을 칸이 없었다. 그래서 같은 표에
     * 특가가를 {@code base_price} 에 넣은 것과 정상가를 넣고 특가는 이름에만 남긴 것이 섞여 있었고,
     * <b>연 절감액(= 월 × 12)과 화면의 6·12개월 토글이 그 요금제들에서 틀린 값을 보여 주고 있었다.</b>
     * 운영 1,706건 중 13건이다. 사용자가 실제 결과에서 찾았다.
     *
     * <p>V32 가 {@code promo_months}·{@code regular_price} 를 더했다. 종료 후 금액을 알면 그만큼
     * 반영하고, <b>모르면 그 기간의 절감액을 {@code null} 로 둔다</b> — 0 을 주면 "안 아낀다"는
     * 다른 거짓말이 된다. 추정값은 넣지 않는다(D-43).
     */
    @Test
    void g66_aPromotionalPlanDoesNotPretendToKnowThePeriodTotal() throws Exception {
        jdbc.execute("""
                INSERT INTO mobile_plan(id,carrier_id,name,network_type,base_price,data_mb,voice_min,sms_cnt,
                    promo_months,regular_price,source_url,collected_at)
                VALUES (7,2,'7개월 특가(이후 모름)','LTE',1000,999999,999999,9999,7,NULL,'http://seed','2026-09-21')""");
        try {
            // a — 7개월 특가라 6개월은 알고 12개월은 모른다. 기간마다 따로 판정한다.
            mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                    {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},"optional":{"contractType":"NONE"}}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.results[0].planName").value("7개월 특가(이후 모름)"))
                    .andExpect(jsonPath("$.data.results[0].monthlySavings").isNumber())
                    .andExpect(jsonPath("$.data.results[0].semiannualSavings").isNumber())
                    .andExpect(jsonPath("$.data.results[0].annualSavings").doesNotExist())
                    .andExpect(jsonPath("$.data.results[0].promoMonths").value(7))
                    .andExpect(jsonPath("$.data.missingInputs[?(@.field=='promotionPeriod')].impact")
                            .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("그 뒤 금액을 저희가 모릅니다"))));
        } finally {
            jdbc.execute("DELETE FROM mobile_plan WHERE id = 7");
        }

        // b — 종료 후 금액을 알면 기간 값을 낸다. 안내 문구도 그 금액을 적는다.
        jdbc.execute("""
                INSERT INTO mobile_plan(id,carrier_id,name,network_type,base_price,data_mb,voice_min,sms_cnt,
                    promo_months,regular_price,source_url,collected_at)
                VALUES (8,2,'7개월 특가(이후 3만원)','LTE',1000,999999,999999,9999,7,30000,'http://seed','2026-09-21')""");
        try {
            mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                    {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},"optional":{"contractType":"NONE"}}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.results[0].planName").value("7개월 특가(이후 3만원)"))
                    // 기본료 1,000 → 종료 후 30,000. 7개월은 지금 금액, 남은 5개월은 29,000원 비싸다.
                    // 월 절감액이 m 이면 연 절감액은 7m + 5(m - 29,000) = 12m - 145,000 이다.
                    .andExpect(jsonPath("$.data.results[0].regularPrice").value(30000))
                    .andExpect(jsonPath("$.data.missingInputs[?(@.field=='promotionPeriod')].impact")
                            .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("월 30,000원으로 바뀌어요"))));
            String body = mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                    {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},"optional":{"contractType":"NONE"}}"""))
                    .andReturn().getResponse().getContentAsString();
            var top = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data").path("results").get(0);
            long monthly = top.path("monthlySavings").asLong();
            assertThat(top.path("annualSavings").asLong()).isEqualTo(12 * monthly - 5 * 29_000);
            // 6개월은 특가 안이라 그대로 ×6 이다 — 기간마다 따로 판정한다.
            assertThat(top.path("semiannualSavings").asLong()).isEqualTo(6 * monthly);
        } finally {
            jdbc.execute("DELETE FROM mobile_plan WHERE id = 8");
        }

        // c' — 바뀐 뒤 금액이 지금과 **같으면** 아무 말도 하지 않는다. 운영 13건 중 8건이 그렇다
        //      (이름은 "7개월 특가" 인데 출처가 "월 46,200원 7개월 이후 46,200원/월"). 안 바뀌는데
        //      바뀐다고 적으면 읽는 사람은 뭔가 바뀐다고 믿는다. 행은 그대로 둔다 — 확인했다는 사실이다.
        jdbc.execute("""
                INSERT INTO mobile_plan(id,carrier_id,name,network_type,base_price,data_mb,voice_min,sms_cnt,
                    promo_months,regular_price,source_url,collected_at)
                VALUES (9,2,'7개월 특가(금액 그대로)','LTE',1000,999999,999999,9999,7,1000,'http://seed','2026-09-21')""");
        try {
            mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                    {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},"optional":{"contractType":"NONE"}}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.results[0].planName").value("7개월 특가(금액 그대로)"))
                    .andExpect(jsonPath("$.data.results[0].promoMonths").value(7))   // 값은 그대로 싣는다
                    .andExpect(jsonPath("$.data.results[0].annualSavings").isNumber())
                    .andExpect(jsonPath("$.data.missingInputs[?(@.field=='promotionPeriod')]")
                            .value(org.hamcrest.Matchers.empty()));                  // 말은 하지 않는다
        } finally {
            jdbc.execute("DELETE FROM mobile_plan WHERE id = 9");
        }

        // c — 특가가 아니면 아무 말도 하지 않고 기간 값도 그대로 나간다.
        mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},"optional":{"contractType":"NONE"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].planName").value("넷플플랜"))
                .andExpect(jsonPath("$.data.results[0].annualSavings").isNumber())
                .andExpect(jsonPath("$.data.results[0].promoMonths").doesNotExist())
                .andExpect(jsonPath("$.data.missingInputs[?(@.field=='promotionPeriod')]")
                        .value(org.hamcrest.Matchers.empty()));
    }

    /**
     * G-72. <b>조건형 할인은 알리되 순위를 바꾸지 않는다</b>(V34, 사용자 지시 2026-09-21).
     *
     * <p>KB리브모바일 페이지는 {@code 기본료 → 최종 혜택가} 두 단계로 적는데(48,900 → 23,000)
     * <b>그 조건이 페이지 어디에도 없다.</b> SKT 망 요금제는 아예 "최대 할인가" 라고 쓴다 — "최대" 는
     * 누구나 그 값은 아니라는 뜻이다. 조건을 채웠는지 <b>우리는 모른다.</b>
     *
     * <p>그래서 두 가지가 동시에 참이어야 한다. 하나라도 깨지면 사용자가 손해를 본다.
     * <ul>
     *   <li>순위에 <b>안 쓴다</b> — 쓰면 조건을 못 채운 사용자에게 없는 금액을 약속한다(절대 원칙 1)</li>
     *   <li>그래도 <b>알린다</b> — 안 알리면 기본료로 밀려 그 요금제는 영영 안 보인다(G-71 의 남은 한계)</li>
     * </ul>
     */
    @Test
    void g72_conditionalDiscountIsAnnouncedButNeverRanked() throws Exception {
        // 혜택가 3,900원 — 후보 중 가장 싼 기본료(45,000)의 10분의 1 이다. 순위가 이 값을 보면 1순위가 된다.
        jdbc.execute("""
                INSERT INTO mobile_plan(id,carrier_id,name,network_type,base_price,data_mb,voice_min,sms_cnt,
                                        benefit_price,benefit_label,source_url,collected_at)
                VALUES (9,2,'혜택플랜','LTE',60000,100000,999999,9999,3900,'최대 할인가(VAT포함)',
                        'https://m.liivm.com/','2026-09-21')""");
        try {
            String body = mvc.perform(post("/api/v1/recommendations").contentType(MediaType.APPLICATION_JSON).content("""
                    {"required":{"monthlyDataGb":20,"wantedServiceIds":[1]},"optional":{"contractType":"NONE"}}"""))
                    .andExpect(status().isOk())
                    // a — 1순위는 그대로다. 혜택플랜은 기본료 60,000원이라 가장 비싸다.
                    .andExpect(jsonPath("$.data.results[0].planName").value("넷플플랜"))
                    // b — 어떤 결과의 월액도 가장 싼 기본료 밑으로 내려가지 않는다. 내려갔다면 혜택가가 샌 것이다.
                    .andExpect(jsonPath("$.data.results[?(@.monthlyTotal < 45000)]")
                            .value(org.hamcrest.Matchers.empty()))
                    // c — 대신 안내로 나간다. 금액과 **출처가 부르는 이름 그대로**를 싣는다(D-43).
                    .andExpect(jsonPath("$.data.missingInputs[?(@.field=='carrierBenefitCondition')]")
                            .value(org.hamcrest.Matchers.hasSize(1)))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).contains("월 3,900원").contains("최대 할인가(VAT포함)")
                    .contains("기본료 60,000원")     // 순위를 정한 금액이 무엇이었는지 같이 말한다
                    // **후보였다는 사실을 먼저 말한다.** 이 말이 빠지면 화면에 없는 통신사가
                    // 안내에만 나와 "이건 왜 나오나" 가 된다 — 사용자가 실제로 그렇게 물었다.
                    .contains("후보에 있던");
            // d — 조건 자체는 **지어내지 않는다.** 출처에 없는 말이 응답에 있으면 그 순간 D-43 위반이다.
            assertThat(body).doesNotContain("실적").doesNotContain("카드");
        } finally {
            jdbc.execute("DELETE FROM mobile_plan WHERE id = 9");
        }
    }

    private void assertGap(String kind, String queryText, int expectedCount) {
        assertThat(jdbc.queryForObject(
                "SELECT requested_cnt FROM catalog_candidate WHERE kind=? AND query_text=?",
                Integer.class, kind, queryText)).isEqualTo(expectedCount);
    }
}
