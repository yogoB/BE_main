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

    /**
     * b — 이미 서비스명을 품은 등급명에는 또 붙이지 않는다. "크레마클럽 크레마클럽 X FLO 99" 는 안 된다.
     *
     * <p>표본이 <b>원화 등급</b>이어야 한다 — {@code findTiersByIds} 는 해외 결제 등급을 거른다(G-17).
     * "리디셀렉트 해외카드" 로 짚었다가 빈 목록을 받았다.
     */
    @Test void g61b_tierNamesThatAlreadySayTheServiceAreLeftAlone() {
        List<SubscriptionTier> tiers = reader.findTiersByIds(List.of(tierId("크레마클럽", "크레마클럽 X FLO 99")));

        assertThat(tiers).singleElement()
                .extracting(SubscriptionTier::name).isEqualTo("크레마클럽 X FLO 99");
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

    /**
     * e — <b>낱말만 겹치는 경우.</b> 전체 포함만 보던 첫 규칙이 이걸 놓쳐 운영 설명 문장에
     * "카카오 이모티콘 플러스 이모티콘 플러스" 가 나갔다(내레이터 세션이 찾아 알려 줬다, 2026-09-21).
     * 서비스명 끝과 등급명 앞이 겹치면 접고, 등급명이 이미 브랜드를 말하면 그대로 둔다.
     */
    @Test void g61e_overlappingWordsAreFoldedInsteadOfRepeated() {
        assertThat(CatalogReader.tierDisplayName("YouTube Music", "Music Premium 개인"))
                .isEqualTo("YouTube Music Premium 개인");
        assertThat(CatalogReader.tierDisplayName("카카오 이모티콘 플러스", "이모티콘 플러스"))
                .isEqualTo("카카오 이모티콘 플러스");          // 겹친 부분이 등급명 전부다
        assertThat(CatalogReader.tierDisplayName("Google One", "Google AI Plus 2TB"))
                .isEqualTo("Google AI Plus 2TB");        // 등급명이 이미 브랜드를 말한다
        assertThat(CatalogReader.tierDisplayName("Google One", "Basic 100GB"))
                .isEqualTo("Google One Basic 100GB");    // 안 겹치면 그대로 붙인다
    }

    /**
     * f — 카탈로그 <b>전체</b>에서 표시명이 겹치지 않는다. 겹치면 c 가 막으려던 모호함이 되돌아온다.
     * 규칙이 자바에 있으므로 SQL 로 흉내 내지 않고 <b>같은 메서드를 돌려</b> 확인한다 —
     * 사본을 만들면 둘이 어긋나는 날이 온다.
     */
    @Test void g61f_noTwoActiveTiersShareADisplayName() {
        List<String> labels = jdbc.query("""
                SELECT s.name AS service_name, t.name AS tier_name
                  FROM subscription_tier t JOIN subscription_service s ON s.id = t.service_id
                 WHERE t.active AND s.active""",
                (rs, i) -> CatalogReader.tierDisplayName(rs.getString("service_name"), rs.getString("tier_name")));

        assertThat(labels).hasSizeGreaterThan(100).doesNotHaveDuplicates();
    }

    /**
     * G-68. <b>퇴역한 등급의 이름을 다시 쓸 수 있다</b>(V33, 2026-09-21).
     *
     * <p>이 테스트가 없어서 운영을 5분 죽였다. `UNIQUE (service_id, name)` 이 <b>비활성 행에도</b>
     * 걸려, 합본에서 내린 등급이 이름을 영원히 쥐고 있었다. 중복 등급을 하나로 합치고 살아남은 쪽에
     * 그 이름을 주려다 기동이 실패했다.
     *
     * <p><b>테스트는 전부 통과했었다</b> — 새 DB 에는 퇴역 행이 존재한 적이 없기 때문이다.
     * 그래서 여기서는 <b>운영의 모양을 일부러 만든다</b>: 같은 이름을 쥔 비활성 행을 먼저 넣고,
     * 그 이름을 다른 활성 행에 준다. 퇴역 행이 없는 DB 에서만 도는 테스트는 이 종류를 영영 못 잡는다.
     */
    @Test void g68_aRetiredTierDoesNotHoldItsNameHostage() {
        long service = serviceId("넷플릭스");
        jdbc.update("""
                INSERT INTO subscription_tier (id, service_id, name, price, currency, tax_included, active)
                VALUES (9001, ?, '물러난 등급', 1000, 'KRW', true, FALSE)""", service);
        try {
            // 퇴역 행이 그 이름을 쥐고 있으면 여기서 터진다 — 운영에서 실제로 그렇게 터졌다.
            jdbc.update("""
                    INSERT INTO subscription_tier (id, service_id, name, price, currency, tax_included, active)
                    VALUES (9002, ?, '물러난 등급', 2000, 'KRW', true, TRUE)""", service);

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM subscription_tier WHERE service_id = ? AND name = '물러난 등급'",
                    Integer.class, service)).isEqualTo(2);
            // 퇴역 행은 이름을 그대로 간직한다 — 예전에 그 id 로 저장한 결과가 같은 이름으로 읽혀야 한다.
            assertThat(jdbc.queryForObject(
                    "SELECT name FROM subscription_tier WHERE id = 9001", String.class)).isEqualTo("물러난 등급");
        } finally {
            jdbc.update("DELETE FROM subscription_tier WHERE id IN (9001, 9002)");
        }
    }

    /** G-68 b — <b>활성끼리는 여전히 겹칠 수 없다.</b> 제약을 느슨하게 한 것이지 없앤 것이 아니다. */
    @Test void g68b_activeTiersStillCannotShareAName() {
        long service = serviceId("넷플릭스");
        jdbc.update("""
                INSERT INTO subscription_tier (id, service_id, name, price, currency, tax_included, active)
                VALUES (9003, ?, '겹치면 안 되는 이름', 1000, 'KRW', true, TRUE)""", service);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO subscription_tier (id, service_id, name, price, currency, tax_included, active)
                    VALUES (9004, ?, '겹치면 안 되는 이름', 2000, 'KRW', true, TRUE)""", service))
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        } finally {
            jdbc.update("DELETE FROM subscription_tier WHERE id IN (9003, 9004)");
        }
    }
}
