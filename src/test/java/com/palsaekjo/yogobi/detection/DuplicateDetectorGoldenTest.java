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

    private static final ActiveSubscription NETFLIX_STD = new ActiveSubscription(NETFLIX, 2L, "넷플 스탠다드", 13_500, 13_500);
    private static final ActiveSubscription NETFLIX_PREMIUM = new ActiveSubscription(NETFLIX, 3L, "넷플 프리미엄", 17_000, 17_000);
    private static final ActiveSubscription TVING_STD = new ActiveSubscription(TVING, 8L, "티빙 스탠다드", 13_500, 13_500);
    private static final ActiveSubscription WAVE_STD = new ActiveSubscription(WAVE, 12L, "웨이브 스탠다드", 10_900, 10_900);

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

    /* ── G-09 e·f·g (2026-09-17) — 운영에서 놓치던 것 ──────────────────────── */

    /**
     * G-09 e. 요금제가 서비스를 <b>포함</b>하는데(BUNDLE_INCLUDED) 등급을 밝히지 않은 경우.
     *
     * <p>운영 재현: 회원의 현재 요금제가 KT `초이스 더블 유튜브 프리미엄+넷플릭스` 였고
     * 그 혜택이 `BUNDLE_INCLUDED 넷플릭스(등급 미상)` 였는데, 넷플릭스를 7,000원 따로 결제 중이었다.
     * 이전 코드는 `benefitType == FREE` 만 봐서 <b>아무 말도 하지 않았다.</b>
     */
    @org.junit.jupiter.api.Test
    void g09e_요금제에_포함된_서비스를_따로_결제하면_전액이_낭비다() {
        var included = new PlanBenefit(NETFLIX, null, BenefitType.BUNDLE_INCLUDED, null, false, null);
        var paying = new ActiveSubscription(NETFLIX, 1L, "광고형 스탠다드", 7_000, 7_000);

        assertThat(detector.detect(List.of(paying), List.of(included), List.of()))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.rule()).isEqualTo(DetectionRule.BENEFIT_OVERLAP);
                    assertThat(f.wastedAmount()).isEqualTo(7_000);
                    // 등급을 모르므로 금액을 단정하지 않는다 — 상한이며 표시 전용이다(D-17).
                    assertThat(f.provenance()).isEqualTo(com.palsaekjo.yogobi.common.Provenance.ESTIMATED);
                });
    }

    /** G-09 f. 정액 할인을 안 쓰고 정가로 따로 내는 중이면 그 차액이 낭비다. 금액이 확정되므로 DERIVED. */
    @org.junit.jupiter.api.Test
    void g09f_할인_혜택을_안_쓰고_정가로_내면_차액이_낭비다() {
        // 운영 실물: `베스트 99(넷플릭스)` 가 광고형 스탠다드(정가 7,000)를 6,000 할인 → 실부담 1,000
        var discount = new PlanBenefit(NETFLIX, 1L, BenefitType.FIXED_DISCOUNT,
                java.math.BigDecimal.valueOf(6_000), false, null);
        var payingList = new ActiveSubscription(NETFLIX, 1L, "광고형 스탠다드", 7_000, 7_000);

        assertThat(detector.detect(List.of(payingList), List.of(discount), List.of()))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.wastedAmount()).isEqualTo(6_000);
                    assertThat(f.provenance()).isEqualTo(com.palsaekjo.yogobi.common.Provenance.DERIVED);
                });
    }

    /** G-09 e 보강: 등급까지 밝힌 포함 혜택이면 금액이 확정되므로 DERIVED 다. */
    @org.junit.jupiter.api.Test
    void g09e2_등급까지_밝힌_포함_혜택은_추정이_아니다() {
        var included = new PlanBenefit(NETFLIX, 2L, BenefitType.BUNDLE_INCLUDED, null, false, null);

        assertThat(detector.detect(List.of(NETFLIX_STD), List.of(included), List.of()))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.wastedAmount()).isEqualTo(13_500);
                    assertThat(f.provenance()).isEqualTo(com.palsaekjo.yogobi.common.Provenance.DERIVED);
                });
    }
}
