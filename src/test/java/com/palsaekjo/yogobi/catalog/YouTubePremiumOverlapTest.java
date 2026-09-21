package com.palsaekjo.yogobi.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.palsaekjo.yogobi.pricing.domain.BundleProduct;
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
 * G-63. 유튜브 프리미엄은 YouTube Music 을 포함한다 — <b>겹치는 구독을 우리 카탈로그가 먼저 알아야 한다.</b>
 *
 * <p>카탈로그가 이렇게 적혀 있었다(2026-09-21 발견): 8,500원 라이트에 "YouTube Music 앱 포함",
 * 14,900원 프리미엄에 "YouTube Music 앱 제외". <b>실제와 정반대다.</b> 게다가 같은 상품이
 * 이름만 다르게 두 행이었다(`유튜브 프리미엄 라이트` id 16 / `Premium Lite` id 19, 둘 다 8,500원).
 *
 * <p>메모만 고치면 돈은 그대로 틀린다 — 계산기는 고른 등급의 금액을 더할 뿐이다. 운영에서
 * 유튜브 프리미엄 + YouTube Music 을 고르면 {@code 14,900 + 11,990 = 26,890원} 이 나왔다.
 * 실제로는 14,900원이 끝이고, <b>월 11,990원을 과다 계산</b>하고 있었다. "겹치는 구독을 찾아준다"는
 * 서비스가 정작 자기 카탈로그의 겹침을 못 보고 있었던 것이다.
 *
 * <p>고치는 방법은 번들이다. 기존 기계를 그대로 쓴다 — 구성 등급을 <b>전부</b> 원할 때만 적용되고,
 * 하나만 원하면 각각 제 가격으로 나간다.
 *
 * <p>1차 출처: Google 공식 YouTube 고객센터
 * {@code https://support.google.com/youtube/answer/6308116?hl=ko} —
 * "YouTube Premium 혜택의 일환으로 YouTube Music 앱에서 광고로 끊김 없이 오프라인으로 이동 중에도
 * 음악을 즐길 수 있습니다." 가격 8,500 / 14,900 은 유튜브 공식 블로그
 * {@code https://blog.youtube/intl/ko-kr/news-and-events/yt-premium-lite-kr/}. 확인일 2026-09-21.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "CATALOG_CSV_DIR=")
@Testcontainers
class YouTubePremiumOverlapTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    private static final long YOUTUBE_PREMIUM = 17;
    private static final long YOUTUBE_MUSIC = 61;

    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired CatalogReader reader;
    @Autowired JdbcTemplate jdbc;

    /** a — 둘 다 원하면 번들 하나로 접힌다. 더하면 26,890원인데 실제로는 14,900원이 끝이다. */
    @Test void g63a_wantingBothCollapsesIntoOneBundle() {
        List<BundleProduct> bundles = reader.findApplicableBundles(java.util.Set.of(YOUTUBE_PREMIUM, YOUTUBE_MUSIC));

        assertThat(bundles).extracting(BundleProduct::price).contains(14_900L);
        assertThat(bundles).filteredOn(b -> b.price() == 14_900L).singleElement()
                .satisfies(b -> assertThat(b.tierIds()).containsExactlyInAnyOrder(YOUTUBE_PREMIUM, YOUTUBE_MUSIC));
    }

    /**
     * b — <b>하나만 원하면 번들이 안 붙는다.</b> 번들은 구성 등급을 전부 원할 때만 적용된다.
     * 음악만 듣는 사람에게 영상 요금까지 물리면 그게 새 거짓말이다.
     */
    @Test void g63b_wantingOnlyOneDoesNotApplyTheBundle() {
        assertThat(reader.findApplicableBundles(java.util.Set.of(YOUTUBE_MUSIC)))
                .noneMatch(b -> b.tierIds().contains(YOUTUBE_PREMIUM));
        assertThat(reader.findApplicableBundles(java.util.Set.of(YOUTUBE_PREMIUM)))
                .noneMatch(b -> b.tierIds().contains(YOUTUBE_MUSIC));
    }

    /** c — 프리미엄 메모가 실제와 같다. 반대로 적혀 있었다. */
    @Test void g63c_theNoteSaysMusicIsIncluded() {
        String note = jdbc.queryForObject(
                "SELECT note FROM subscription_tier WHERE id = ?", String.class, YOUTUBE_PREMIUM);

        assertThat(note).contains("YouTube Music").doesNotContain("제외");
    }

    /**
     * d — 같은 상품이 이름만 다르게 둘 있던 것을 하나로 합쳤다. 남긴 쪽은 공식 표기에 가깝고
     * 설명도 정확한 {@code Premium Lite}(id 19) 다.
     */
    @Test void g63d_theDuplicateLiteTierIsGone() {
        List<SubscriptionTier> lite = reader.findTiersByIds(List.of(16L, 19L));

        assertThat(lite).extracting(SubscriptionTier::id).containsExactly(19L);
        // 이름은 공식 한국어 표기다. 퇴역한 16 이 그 이름을 쥐고 있어 한 번 실패했다 — V33 이 풀었다(G-68).
        assertThat(lite).singleElement()
                .extracting(SubscriptionTier::name).isEqualTo("유튜브 프리미엄 라이트");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM subscription_tier WHERE service_id = 6 AND active", Integer.class))
                .isEqualTo(2);   // 프리미엄 + 라이트
    }

    /**
     * e — 중복이던 id 16 은 <b>어디서도 활성이 아니다.</b>
     *
     * <p>새 DB 에서는 애초에 안 들어가고, 이미 있던 DB(운영)에서는 합본에서 사라진 행이 비활성으로
     * 내려간다(G-53). 두 경우 모두 "활성 아님"이라 <b>한 단언으로 덮인다</b> —
     * `active = false` 로 못박으면 새 DB 에서는 행이 없어 거짓 실패가 난다.
     *
     * <p><b>지우지 않고 내리는 이유</b>: 예전에 id 16 으로 저장한 결과가 계속 읽혀야 한다.
     * 퇴역 경로 자체는 G-53 이 검증한다.
     */
    @Test void g63e_theDuplicateTierIsNotActiveAnywhere() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM subscription_tier WHERE id = 16 AND active", Integer.class)).isZero();
    }
}
