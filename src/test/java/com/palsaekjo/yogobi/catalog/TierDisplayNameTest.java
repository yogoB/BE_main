package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.palsaekjo.yogobi.pricing.domain.SubscriptionTier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * G-61. 계산 내역의 등급 이름은 <b>서비스까지 말한다.</b>
 *
 * <p>{@code subscription_tier.name} 은 "스탠다드"·"프리미엄"처럼 서비스 없이는 읽히지 않는 이름이
 * 대부분이다 — 넷플릭스·디즈니+·티빙·왓챠가 전부 "스탠다드"를 갖고 있어, 둘을 고르면
 * <b>같은 라벨 두 줄이 금액만 다르게</b> 뜬다. 운영 응답이 실제로 그랬다(2026-09-21 페르소나 점검).
 *
 * <p>골든 테스트는 처음부터 {@code "넷플릭스 스탠다드"} 를 가정해 왔고 {@code docs/BE_API.md} 의
 * 응답 예시도 그렇다 — <b>테스트가 쓰는 이름과 DB 가 주는 이름이 달라서 통과하면서 틀렸다.</b>
 * 여기서 그 둘을 붙인다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "CATALOG_CSV_DIR=")
@Testcontainers
class TierDisplayNameTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired CatalogReader reader;
    @Autowired JdbcTemplate jdbc;

    private long tierId(String service, String tier) {
        return jdbc.queryForObject("""
                SELECT t.id FROM subscription_tier t JOIN subscription_service s ON s.id = t.service_id
                 WHERE s.name = ? AND t.name = ?""", Long.class, service, tier);
    }

    private long serviceId(String name) {
        return jdbc.queryForObject("SELECT id FROM subscription_service WHERE name = ?", Long.class, name);
    }

    /** a — 서비스 없이는 못 읽는 등급명 앞에 서비스를 붙인다. */
    @Test void g61a_genericTierNamesCarryTheirService() {
        List<SubscriptionTier> tiers = reader.findTiersByIds(List.of(tierId("넷플릭스", "스탠다드")));

        assertThat(tiers).singleElement()
                .extracting(SubscriptionTier::name).isEqualTo("넷플릭스 스탠다드");
    }

    /** b — 이미 서비스명을 품은 등급명에는 또 붙이지 않는다. "유튜브 프리미엄 유튜브 프리미엄 라이트" 는 안 된다. */
    @Test void g61b_tierNamesThatAlreadySayTheServiceAreLeftAlone() {
        List<SubscriptionTier> tiers = reader.findTiersByIds(List.of(tierId("유튜브 프리미엄", "유튜브 프리미엄 라이트")));

        assertThat(tiers).singleElement()
                .extracting(SubscriptionTier::name).isEqualTo("유튜브 프리미엄 라이트");
    }

    /**
     * c — <b>이것이 이 케이스의 이유다.</b> 서로 다른 서비스의 같은 등급명이 한 계산 안에 같이 오면
     * 라벨만 보고는 구분이 안 됐다. 이제 다르다.
     */
    @Test void g61c_sameTierNameFromDifferentServicesIsNoLongerAmbiguous() {
        List<SubscriptionTier> tiers = reader.findTiersByIds(
                List.of(tierId("넷플릭스", "스탠다드"), tierId("디즈니+", "스탠다드")));

        assertThat(tiers).extracting(SubscriptionTier::name)
                .containsExactlyInAnyOrder("넷플릭스 스탠다드", "디즈니+ 스탠다드");
    }

    /**
     * d — 대표 등급 고르기가 <b>이름을 바꾼 뒤에도 같은 것을 고른다.</b> 그 규칙이 이름에
     * "스탠다드"·"광고" 가 들어 있는지로 판정하므로, 접두가 그걸 깨지 않는지 고정한다.
     */
    @Test void g61d_representativeTierIsStillTheSameOneAfterRenaming() {
        List<SubscriptionTier> tiers = reader.findRepresentativeTiers(List.of(serviceId("넷플릭스")));

        assertThat(tiers).singleElement()
                .extracting(SubscriptionTier::name).isEqualTo("넷플릭스 스탠다드");   // 광고형이 더 싸지만 고르지 않는다
    }

    /** e — 카탈로그 전체에서 표시명이 겹치지 않는다. 겹치면 c 가 막으려던 모호함이 되돌아온다. */
    @Test void g61e_noTwoActiveTiersShareADisplayName() {
        List<String> duplicated = jdbc.queryForList("""
                SELECT CASE WHEN position(s.name in t.name) > 0 THEN t.name
                            ELSE s.name || ' ' || t.name END AS label
                  FROM subscription_tier t JOIN subscription_service s ON s.id = t.service_id
                 WHERE t.active AND s.active
                 GROUP BY 1 HAVING count(*) > 1""", String.class);

        assertThat(duplicated).isEmpty();
    }
}
