package com.palsaekjo.yogobi.detection;

import static org.assertj.core.api.Assertions.assertThat;

import com.palsaekjo.yogobi.common.BenefitType;
import com.palsaekjo.yogobi.common.DetectionRule;
import com.palsaekjo.yogobi.detection.domain.ActiveSubscription;
import com.palsaekjo.yogobi.detection.domain.DetectionFinding;
import com.palsaekjo.yogobi.pricing.domain.BundleProduct;
import com.palsaekjo.yogobi.pricing.domain.PlanBenefit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** docs/testing.md 골든 케이스 G-09. 혜택이 있어도 다른 서비스를 결제 중이면 중복이 아니다(d). */
class DuplicateDetectorGoldenTest {

    private static final long NETFLIX = 1;
    private static final long TVING = 3;
    private static final long WAVE = 4;

    private static final ActiveSubscription NETFLIX_STD = new ActiveSubscription(NETFLIX, 2L, "넷플 스탠다드", 13_500);
    private static final ActiveSubscription NETFLIX_PREMIUM = new ActiveSubscription(NETFLIX, 3L, "넷플 프리미엄", 17_000);
    private static final ActiveSubscription TVING_STD = new ActiveSubscription(TVING, 8L, "티빙 스탠다드", 13_500);
    private static final ActiveSubscription WAVE_STD = new ActiveSubscription(WAVE, 12L, "웨이브 스탠다드", 10_900);

    private static final BundleProduct B4 =
            new BundleProduct(4, "티빙x웨이브 더블 스탠다드", 15_000, Set.of(8L, 12L));

    private static PlanBenefit free(long serviceId) {
        return new PlanBenefit(serviceId, null, BenefitType.FREE, null, false, null);
    }

    private final DuplicateDetector detector = new DuplicateDetector();

    @Test
    void g09a_benefitOverlap_웨이브무료인데_결제중() {
        List<DetectionFinding> findings = detector.detect(
                List.of(WAVE_STD), List.of(free(WAVE)), List.of());

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo(DetectionRule.BENEFIT_OVERLAP);
            assertThat(f.wastedAmount()).isEqualTo(10_900);
        });
    }

    @Test
    void g09b_tierDuplicate_넷플_스탠다드와_프리미엄_동시결제() {
        List<DetectionFinding> findings = detector.detect(
                List.of(NETFLIX_STD, NETFLIX_PREMIUM), List.of(), List.of());

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo(DetectionRule.TIER_DUPLICATE);
            assertThat(f.wastedAmount()).isEqualTo(13_500);
        });
    }

    @Test
    void g09c_bundleOverlap_티빙_웨이브_개별결제() {
        List<DetectionFinding> findings = detector.detect(
                List.of(TVING_STD, WAVE_STD), List.of(), List.of(B4));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo(DetectionRule.BUNDLE_OVERLAP);
            assertThat(f.wastedAmount()).isEqualTo(9_400);
        });
    }

    @Test
    void g09d_혜택있어도_다른서비스_결제면_탐지없음() {
        // 넷플 무료 혜택 + 웨이브 결제 → 겹치지 않는다
        List<DetectionFinding> findings = detector.detect(
                List.of(WAVE_STD), List.of(free(NETFLIX)), List.of());

        assertThat(findings).isEmpty();
    }
}
