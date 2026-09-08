package com.palsaekjo.yogobi.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.palsaekjo.yogobi.common.Accuracy;
import com.palsaekjo.yogobi.common.BenefitType;
import com.palsaekjo.yogobi.common.ContractType;
import com.palsaekjo.yogobi.pricing.domain.BundleProduct;
import com.palsaekjo.yogobi.pricing.domain.CostBreakdown;
import com.palsaekjo.yogobi.pricing.domain.MobilePlan;
import com.palsaekjo.yogobi.pricing.domain.PlanBenefit;
import com.palsaekjo.yogobi.pricing.domain.PricingContext;
import com.palsaekjo.yogobi.pricing.domain.SubscriptionTier;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** docs/testing.md 골든 케이스 G-01~G-08. 픽스처는 문서와 동일한 숫자를 그대로 옮긴다. */
class CostCalculatorGoldenTest {

    private static final long NETFLIX = 1;
    private static final long TVING = 2;
    private static final long WAVE = 3;

    private static final SubscriptionTier T2 = new SubscriptionTier(2, NETFLIX, "넷플릭스 스탠다드", 13_500);
    private static final SubscriptionTier T6 = new SubscriptionTier(6, TVING, "티빙 광고형", 5_500);
    private static final SubscriptionTier T8 = new SubscriptionTier(8, TVING, "티빙 스탠다드", 13_500);
    private static final SubscriptionTier T12 = new SubscriptionTier(12, WAVE, "웨이브 스탠다드", 10_900);

    private static final BundleProduct B4 =
            new BundleProduct(4, "티빙×웨이브 더블 스탠다드", 15_000, Set.of(8L, 12L));

    private static final MobilePlan P1 = new MobilePlan(1, "넷플릭스형", 55_000, 0,
            List.of(free(NETFLIX, 2L, null)));
    private static final MobilePlan P2 = new MobilePlan(2, "웨이브형", 45_000, 0,
            List.of(free(WAVE, 12L, null)));
    private static final MobilePlan P3 = new MobilePlan(3, "택1형", 50_000, 0,
            List.of(free(NETFLIX, 2L, "OTT_PICK_ONE"),
                    free(TVING, 6L, "OTT_PICK_ONE"),
                    free(WAVE, 12L, "OTT_PICK_ONE")));
    private static final MobilePlan P4 = new MobilePlan(4, "정액할인형", 48_000, 0,
            List.of(new PlanBenefit(NETFLIX, 2L, BenefitType.FIXED_DISCOUNT,
                    BigDecimal.valueOf(4_000), false, null)));
    private static final MobilePlan P5 = new MobilePlan(5, "절사검증형", 33_333, 0, List.of());

    private final CostCalculator calculator = new CostCalculator();

    private static PlanBenefit free(long serviceId, Long tierId, String exclusiveGroup) {
        return new PlanBenefit(serviceId, tierId, BenefitType.FREE, null,
                exclusiveGroup != null, exclusiveGroup);
    }

    private static PricingContext ctx(ContractType contractType) {
        return new PricingContext(contractType, true, 0, List.of());
    }

    @Test
    void g01a_넷플릭스를_원하면_P1이_유리() {
        assertThat(calculator.calculate(P1, Set.of(T2), ctx(ContractType.NONE)).effectiveMonthlyCost())
                .isEqualTo(55_000);
        assertThat(calculator.calculate(P2, Set.of(T2), ctx(ContractType.NONE)).effectiveMonthlyCost())
                .isEqualTo(58_500);
    }

    @Test
    void g01b_웨이브를_원하면_P2가_유리하고_P1에는_넷플릭스_줄이_없다() {
        CostBreakdown p1 = calculator.calculate(P1, Set.of(T12), ctx(ContractType.NONE));
        CostBreakdown p2 = calculator.calculate(P2, Set.of(T12), ctx(ContractType.NONE));

        assertThat(p1.effectiveMonthlyCost()).isEqualTo(65_900);
        assertThat(p2.effectiveMonthlyCost()).isEqualTo(45_000);
        assertThat(p1.lines()).noneMatch(line -> line.label().equals(T2.name()));
    }

    @Test
    void g02_선택약정_25퍼센트() {
        MobilePlan plan55000 = new MobilePlan(90, "기본료검증", 55_000, 0, List.of());
        MobilePlan plan33333 = new MobilePlan(91, "절사검증", 33_333, 0, List.of());

        assertThat(calculator.calculate(plan55000, Set.of(), ctx(ContractType.SELECTIVE_25))
                .effectiveMonthlyCost()).isEqualTo(41_250);
        assertThat(calculator.calculate(plan55000, Set.of(), ctx(ContractType.NONE))
                .effectiveMonthlyCost()).isEqualTo(55_000);
        assertThat(calculator.calculate(plan33333, Set.of(), ctx(ContractType.SELECTIVE_25))
                .effectiveMonthlyCost()).isEqualTo(24_999);
    }

    @Test
    void g03_할인은_약정할인_다음_선택약정_순서로_적용된다() {
        MobilePlan plan = new MobilePlan(92, "순서검증", 55_000, 4_800, List.of());

        assertThat(calculator.calculate(plan, Set.of(), ctx(ContractType.SELECTIVE_25))
                .effectiveMonthlyCost()).isEqualTo(37_650);
    }

    @Test
    void g04_번들이_개별합보다_싸면_번들_한줄로_계산된다() {
        PricingContext ctxWithBundle = new PricingContext(ContractType.NONE, true, 0, List.of(B4));

        CostBreakdown result = calculator.calculate(P5, Set.of(T8, T12), ctxWithBundle);

        assertThat(result.effectiveMonthlyCost()).isEqualTo(48_333);
        assertThat(result.lines()).filteredOn(line -> line.label().equals(B4.name())).hasSize(1);
        assertThat(result.lines())
                .noneMatch(line -> line.label().equals(T8.name()) || line.label().equals(T12.name()));
    }

    @Test
    void g05_택1_혜택은_절감액이_큰_서비스에_적용된다() {
        CostBreakdown result = calculator.calculate(P3, Set.of(T2, T6), ctx(ContractType.NONE));

        assertThat(result.effectiveMonthlyCost()).isEqualTo(55_500);
    }

    @Test
    void g06_정액할인은_0원_아래로_내려가지_않는다() {
        assertThat(calculator.calculate(P4, Set.of(T2), ctx(ContractType.NONE)).effectiveMonthlyCost())
                .isEqualTo(57_500);

        MobilePlan overDiscount = new MobilePlan(93, "과할인검증", 10_000, 0,
                List.of(new PlanBenefit(NETFLIX, 2L, BenefitType.FIXED_DISCOUNT,
                        BigDecimal.valueOf(99_999), false, null)));
        assertThat(calculator.calculate(overDiscount, Set.of(T2), ctx(ContractType.NONE))
                .effectiveMonthlyCost()).isEqualTo(10_000);
    }

    @Test
    void g07_baseline은_할인_없는_정가_합이다() {
        CostBreakdown result = calculator.calculate(P1, Set.of(T2), ctx(ContractType.NONE));

        assertThat(result.baseline()).isEqualTo(68_500);
        assertThat(result.effectiveMonthlyCost()).isEqualTo(55_000);
        assertThat(result.monthlySavings()).isEqualTo(13_500);
        assertThat(result.annualSavings()).isEqualTo(162_000);
    }

    @Test
    void g08_가족결합_미입력이면_PARTIAL이고_missingInputs에_담긴다() {
        PricingContext missing = new PricingContext(ContractType.NONE, null, 0, List.of());
        CostBreakdown partial = calculator.calculate(P1, Set.of(T2), missing);
        assertThat(partial.accuracy()).isEqualTo(Accuracy.PARTIAL);
        assertThat(partial.missingInputs()).contains("hasFamilyBundle");

        PricingContext full = new PricingContext(ContractType.NONE, false, 0, List.of());
        CostBreakdown fullResult = calculator.calculate(P1, Set.of(T2), full);
        assertThat(fullResult.accuracy()).isEqualTo(Accuracy.FULL);
        assertThat(fullResult.missingInputs()).isEmpty();
    }
}
