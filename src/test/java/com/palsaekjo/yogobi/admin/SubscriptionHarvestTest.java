package com.palsaekjo.yogobi.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.palsaekjo.yogobi.catalog.CatalogChangeRequests;
import com.palsaekjo.yogobi.catalog.SubscriptionPriceOracle;
import com.palsaekjo.yogobi.user.AdminAccount;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
 * G-48 — 구독 공식가 대조(D-60). 저쪽(내레이터)은 공식 페이지를 읽어 원문을 인용할 뿐이고,
 * <b>대조·제안은 전부 여기서 한다.</b> 값이 카탈로그와 다를 때만, 그리고 출처가 같을 때만 제안한다.
 */
@SpringBootTest(properties = {
        "JWT_SECRET=VHlwZS1vbmx5LXRlc3Qta2V5LTMyaGFyYWN0ZXJzLW9yLW1vcmUh",
        "AUTH_SECURE_COOKIES=false", "ADMIN_ID=yogogo", "ADMIN_PASSWORD=test-harvest-password",
        "yogobi.harvest.cron=0 0 9 1 1 ?"})
@Testcontainers
class SubscriptionHarvestTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** 카탈로그의 Spotify. 등급 이름·금액·출처는 시드(`catalog_combined.csv`)가 정한다. */
    private static final String SPOTIFY_URL = "https://www.spotify.com/kr-ko/premium/";

    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogChangeRequests requests;
    @Autowired AdminAccount admin;
    @Autowired AdminActions actions;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM catalog_change_request");
        jdbc.update("DELETE FROM admin_action");
    }

    /** 공식가가 우리와 다르면 제안 1건. 허용 오차가 0 이라 <b>1원 차이도</b> 잡는다. */
    @Test
    void proposesWhenTheOfficialPriceDiffersByEvenOneWon() {
        int made = harvest(check(SPOTIFY_URL,
                offer("Premium Basic", 8691),                 // 카탈로그 8,690 — 1원 다르다
                offer("Premium Individual", 11990)));         // 같다 — 제안하지 않는다

        assertThat(made).isEqualTo(1);
        Map<String, Object> proposal = jdbc.queryForMap(
                "SELECT dataset, row_key, payload::text, reason FROM catalog_change_request");
        assertThat(proposal.get("dataset")).isEqualTo("subscription_tier");
        assertThat(proposal.get("row_key")).isEqualTo("55");                 // Premium Basic 의 등급 id
        assertThat((String) proposal.get("payload")).contains("8691");
        assertThat((String) proposal.get("reason")).contains("8690원 → 8691원").contains("원문:");
    }

    /**
     * URL 드리프트 가드. 같은 URL 이 두 레포에 따로 살아 있으므로, 저쪽이 읽은 페이지가 우리
     * {@code official_url} 과 다르면 <b>그 서비스는 통째로 건너뛴다.</b> 엉뚱한 페이지의 가격이
     * 제안으로 올라오는 것이 조용한 실패 중 가장 나쁘다.
     */
    @Test
    void skipsTheWholeServiceWhenTheSourceUrlDrifted() {
        int made = harvest(check("https://www.spotify.com/kr-ko/premium/individual/",
                offer("Premium Basic", 999)));

        assertThat(made).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_change_request", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM admin_action WHERE action = 'SUBSCRIPTION_SOURCE_DRIFT'", Integer.class))
                .isEqualTo(1);
    }

    /** 조회 실패는 제안 없이 지나가되 <b>운영 타임라인에 남는다</b> — 로그만 남기면 다음 날 아무도 모른다. */
    @Test
    void recordsTheFailureCodeInsteadOfGuessing() {
        int made = harvest(new Stub(null) {
            @Override
            public Check check(String serviceName) {
                throw new Unavailable("CATALOG-SOURCE-CHANGED", "월 정가가 유일하지 않다");
            }
        });

        assertThat(made).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT detail FROM admin_action WHERE action = 'SUBSCRIPTION_CHECK_FAILED'", String.class))
                .contains("CATALOG-SOURCE-CHANGED");
    }

    /** 원화·월 결제가 아니거나 우리에게 없는 등급 이름은 짝을 지어내지 않고 넘긴다. */
    @Test
    void ignoresForeignCurrencyYearlyAndUnknownTiers() {
        int made = harvest(check(SPOTIFY_URL,
                new SubscriptionPriceOracle.Offer("Premium Basic", 5, "USD", "MONTH", "$5"),
                new SubscriptionPriceOracle.Offer("Premium Individual", 119900, "KRW", "YEAR", "연 119,900원"),
                new SubscriptionPriceOracle.Offer("Premium Platinum", 30000, "KRW", "MONTH", "월 30,000원")));

        assertThat(made).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_change_request", Integer.class)).isZero();
    }

    /** 대상은 Spotify 하나로 좁힌다 — 시드 카탈로그가 정답인 테스트라 서비스가 늘면 기대값이 흔들린다. */
    private int harvest(SubscriptionPriceOracle oracle) {
        var harvester = new CatalogDailyHarvest(jdbc, requests, admin, oracle, actions, 0, 20, List.of("Spotify"));
        return (int) harvester.harvest().get("proposedSubscriptionTiers");
    }

    private SubscriptionPriceOracle.Offer offer(String tierName, long price) {
        return new SubscriptionPriceOracle.Offer(tierName, price, "KRW", "MONTH", "월 " + price + "원");
    }

    private Stub check(String sourceUrl, SubscriptionPriceOracle.Offer... offers) {
        return new Stub(sourceUrl, offers);
    }

    /** 조회 결과를 고정한 스텁. 외부를 부르지 않는다 — 대조 규칙만 본다. */
    private static class Stub extends SubscriptionPriceOracle {
        private final String sourceUrl;
        private final List<Offer> offers;

        Stub(String sourceUrl, Offer... offers) {
            super("http://stub.invalid", "", 0);
            this.sourceUrl = sourceUrl;
            this.offers = List.of(offers);
        }

        @Override
        public Check check(String serviceName) {
            return new Check(serviceName, sourceUrl, "2026-09-20T00:00:00Z", "sha256:test", offers);
        }
    }
}
