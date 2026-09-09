package com.palsaekjo.yogobi.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.palsaekjo.yogobi.common.MatchType;
import com.palsaekjo.yogobi.subscription.domain.MerchantAlias;
import java.util.List;
import org.junit.jupiter.api.Test;

/** docs/testing.md 골든 케이스 G-10. 모르는 가맹점은 null(빈 Optional) — 유사도 억지 매핑 금지. */
class MerchantNormalizerGoldenTest {

    // docs/data.md §6 별칭
    private static final List<MerchantAlias> ALIASES = List.of(
            new MerchantAlias(1, "NETFLIX", MatchType.CONTAINS),
            new MerchantAlias(1, "넷플릭스", MatchType.CONTAINS),
            new MerchantAlias(4, "WAVVE", MatchType.CONTAINS),
            new MerchantAlias(4, "콘텐츠웨이브", MatchType.CONTAINS),
            new MerchantAlias(6, "GOOGLE *YOUTUBE", MatchType.PREFIX));

    private final MerchantNormalizer normalizer = new MerchantNormalizer();

    @Test
    void g10_가맹점명을_serviceId로_정규화하고_모르는곳은_null() {
        assertThat(normalizer.resolve("NETFLIX.COM", ALIASES)).contains(1L);
        assertThat(normalizer.resolve("넷플릭스", ALIASES)).contains(1L);
        assertThat(normalizer.resolve("GOOGLE *YOUTUBEPREMIUM", ALIASES)).contains(6L);
        assertThat(normalizer.resolve("콘텐츠웨이브(주)", ALIASES)).contains(4L);
        // 핵심: 모르는 가맹점 → 빈 Optional (억지 매핑 금지)
        assertThat(normalizer.resolve("배달의민족", ALIASES)).isEmpty();
    }

    @Test
    void g10_대소문자_무시_및_빈입력_방어() {
        assertThat(normalizer.resolve("netflix.com", ALIASES)).contains(1L);
        assertThat(normalizer.resolve("", ALIASES)).isEmpty();
        assertThat(normalizer.resolve(null, ALIASES)).isEmpty();
    }
}
