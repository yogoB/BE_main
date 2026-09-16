package com.palsaekjo.yogobi.detection;

import static org.assertj.core.api.Assertions.assertThat;

import com.palsaekjo.yogobi.common.BenefitType;
import com.palsaekjo.yogobi.common.DetectionRule;
import com.palsaekjo.yogobi.detection.domain.ActiveSubscription;
import com.palsaekjo.yogobi.pricing.domain.BundleProduct;
import com.palsaekjo.yogobi.pricing.domain.PlanBenefit;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * docs/testing.md G-26 — 중복 결제 탐지가 <b>낭비라고 말하지 않아야 하는</b> 경계들.
 *
 * <p>탐지는 "당신은 돈을 버리고 있다"고 말하는 기능이다. 틀린 경고는 틀린 절감액보다 나쁘다 —
 * 사용자가 멀쩡한 구독을 해지한다. 그래서 여기 있는 케이스는 전부 <b>탐지하지 않는 쪽</b>이 정답이다.
 * `pricing` G-25 a·b 와 같은 모양의 경계이며(번들 등급 부족·번들가 ≥ 개별합), 같은 이유로 지켜야 한다.
 */
class DuplicateDetectorEdgeGoldenTest {

    private static final long NETFLIX = 1;
    private static final long TVING = 3;
    private static final long WAVE = 4;

    private static final ActiveSubscription TVING_STD = new ActiveSubscription(TVING, 8L, "티빙 스탠다드", 13_500);
    private static final ActiveSubscription WAVE_STD = new ActiveSubscription(WAVE, 12L, "웨이브 스탠다드", 10_900);

    private final DuplicateDetector detector = new DuplicateDetector();

    /* ── 제휴 혜택 겹침으로 보지 않아야 하는 것 ─────────────────────────────── */

    /** G-26a. 혜택이 특정 등급을 지정했는데 사용자가 <b>다른 등급</b>을 결제 중이면 겹침이 아니다. */
    @Test
    void g26a_다른_등급이면_혜택_겹침이_아니다() {
        var freeStandardOnly = new PlanBenefit(TVING, 8L, BenefitType.FREE, null, false, null);
        var premium = new ActiveSubscription(TVING, 9L, "티빙 프리미엄", 17_000);

        assertThat(detector.detect(List.of(premium), List.of(freeStandardOnly), List.of())).isEmpty();
        // 같은 등급이면 겹침이 맞다 — 경계의 반대편도 같이 고정한다.
        assertThat(detector.detect(List.of(TVING_STD), List.of(freeStandardOnly), List.of()))
                .singleElement()
                .satisfies(f -> assertThat(f.rule()).isEqualTo(DetectionRule.BENEFIT_OVERLAP));
    }

    /** G-26b. 사용자가 <b>등급을 모르는</b> 구독은, 혜택이 등급을 지정했다면 겹침으로 단정하지 않는다. */
    @Test
    void g26b_등급을_모르면_등급_지정_혜택과_겹쳤다고_단정하지_않는다() {
        var freeStandardOnly = new PlanBenefit(TVING, 8L, BenefitType.FREE, null, false, null);
        var unknownTier = new ActiveSubscription(TVING, null, "티빙(등급 모름)", 13_500);

        assertThat(detector.detect(List.of(unknownTier), List.of(freeStandardOnly), List.of())).isEmpty();
        // 혜택이 서비스 전체(tierId=null)라면 등급을 몰라도 겹침이 맞다.
        var freeWholeService = new PlanBenefit(TVING, null, BenefitType.FREE, null, false, null);
        assertThat(detector.detect(List.of(unknownTier), List.of(freeWholeService), List.of())).hasSize(1);
    }

    /** G-26c. 서비스가 다르면 겹침이 아니다. */
    @Test
    void g26c_서비스가_다르면_겹침이_아니다() {
        assertThat(detector.detect(List.of(WAVE_STD),
                List.of(new PlanBenefit(NETFLIX, null, BenefitType.FREE, null, false, null)), List.of()))
                .isEmpty();
    }

    /**
     * G-26d. <b>FREE 가 아닌 혜택</b>은 겹침으로 보지 않는다. 정액·정률 할인은 돈을 내고 쓰는 것이지
     * 무료로 주는 것이 아니다 — "이미 공짜인데 또 낸다"는 경고가 성립하지 않는다.
     */
    @Test
    void g26d_할인_혜택은_겹침이_아니다() {
        for (PlanBenefit paid : List.of(
                new PlanBenefit(TVING, 8L, BenefitType.FIXED_DISCOUNT, BigDecimal.valueOf(4_000), false, null),
                new PlanBenefit(TVING, 8L, BenefitType.RATE_DISCOUNT, new BigDecimal("0.30"), false, null),
                new PlanBenefit(TVING, 8L, BenefitType.BUNDLE_INCLUDED, null, false, null))) {
            assertThat(detector.detect(List.of(TVING_STD), List.of(paid), List.of()))
                    .as("%s 는 무료 제공이 아니다", paid.benefitType())
                    .isEmpty();
        }
    }

    /* ── 번들 겹침으로 보지 않아야 하는 것 ─────────────────────────────────── */

    /** G-26e. 번들 구성 등급을 <b>전부</b> 결제 중이 아니면 번들 낭비가 아니다. */
    @Test
    void g26e_번들_구성_등급을_다_갖고_있지_않으면_낭비가_아니다() {
        var bundle = new BundleProduct(4, "티빙×웨이브 묶음", 15_000, Set.of(8L, 12L));

        assertThat(detector.detect(List.of(TVING_STD), List.of(), List.of(bundle))).isEmpty();
        // 둘 다 결제 중이고 번들이 더 싸면 그때가 낭비다(차액 9,400).
        assertThat(detector.detect(List.of(TVING_STD, WAVE_STD), List.of(), List.of(bundle)))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.rule()).isEqualTo(DetectionRule.BUNDLE_OVERLAP);
                    assertThat(f.wastedAmount()).isEqualTo(13_500 + 10_900 - 15_000);
                });
    }

    /** G-26f. 번들가가 개별 합계보다 <b>비싸거나 같으면</b> 낭비가 아니다. 같을 때도 아니다. */
    @Test
    void g26f_번들이_개별합보다_비싸거나_같으면_낭비가_아니다() {
        long individual = TVING_STD.monthlyAmount() + WAVE_STD.monthlyAmount();   // 24,400
        for (long price : List.of(individual, individual + 1)) {
            var bundle = new BundleProduct(5, "안 싼 묶음", price, Set.of(8L, 12L));
            assertThat(detector.detect(List.of(TVING_STD, WAVE_STD), List.of(), List.of(bundle)))
                    .as("번들가 %d 는 개별합 %d 보다 이득이 아니다", price, individual)
                    .isEmpty();
        }
    }

    /** G-26g. 등급을 모르는 구독은 번들 판정에서 제외한다 — 어느 등급인지 모르면 묶을 수 없다. */
    @Test
    void g26g_등급을_모르는_구독은_번들_판정에_넣지_않는다() {
        var unknownTier = new ActiveSubscription(TVING, null, "티빙(등급 모름)", 13_500);
        var bundle = new BundleProduct(6, "티빙×웨이브 묶음", 15_000, Set.of(8L, 12L));

        assertThat(detector.detect(List.of(unknownTier, WAVE_STD), List.of(), List.of(bundle))).isEmpty();
    }
}
