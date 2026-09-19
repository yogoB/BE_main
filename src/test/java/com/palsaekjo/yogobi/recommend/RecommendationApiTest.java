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

    private void assertGap(String kind, String queryText, int expectedCount) {
        assertThat(jdbc.queryForObject(
                "SELECT requested_cnt FROM catalog_candidate WHERE kind=? AND query_text=?",
                Integer.class, kind, queryText)).isEqualTo(expectedCount);
    }
}
