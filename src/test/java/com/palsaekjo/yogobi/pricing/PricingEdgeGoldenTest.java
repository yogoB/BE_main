package com.palsaekjo.yogobi.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.palsaekjo.yogobi.common.BenefitType;
import com.palsaekjo.yogobi.common.ContractType;
import com.palsaekjo.yogobi.pricing.domain.BundleProduct;
import com.palsaekjo.yogobi.pricing.domain.Money;
import com.palsaekjo.yogobi.pricing.domain.MobilePlan;
import com.palsaekjo.yogobi.pricing.domain.PlanBenefit;
import com.palsaekjo.yogobi.pricing.domain.PricingContext;
import com.palsaekjo.yogobi.pricing.domain.SubscriptionTier;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * docs/testing.md G-25 — 골든 케이스가 지나친 `pricing` 분기.
 *
 * <p>`docs/testing.md` 기준 절은 `pricing/**` 에 <b>분기 100%</b> 를 요구해 왔지만 재는 도구가 없어
 * 한 번도 검증되지 않았다(2026-09-17 측정: 분기 87.5%, 9개 미달). 여기서 채우는 것은 전부
 * <b>금액이 달라지는 분기</b>다 — 커버리지 숫자를 올리려고 만든 테스트가 아니라, 틀리면 사용자가
 * 잘못된 금액을 보는 경로다.
 */
class PricingEdgeGoldenTest {

    private static final long NETFLIX = 1;
    private static final long TVING = 2;
    private static final long WAVE = 3;

    private static final SubscriptionTier T2 = new SubscriptionTier(2, NETFLIX, "넷플릭스 스탠다드", 13_500);
    private static final SubscriptionTier T8 = new SubscriptionTier(8, TVING, "티빙 스탠다드", 13_500);
    private static final SubscriptionTier T12 = new SubscriptionTier(12, WAVE, "웨이브 스탠다드", 10_900);

    private final CostCalculator calculator = new CostCalculator();

    private static PricingContext ctx(List<BundleProduct> bundles) {
        return new PricingContext(ContractType.NONE, true, 0, bundles);
    }

    private static MobilePlan plan(List<PlanBenefit> benefits) {
        return new MobilePlan(99, "검증용", 50_000, 0, benefits);
    }

    /* ── 번들을 쓰지 않는 두 경우 (CostCalculator) ───────────────────────────── */

    /**
     * G-25a. 번들에 묶인 등급을 사용자가 <b>전부</b> 원하지 않으면 번들을 쓰지 않는다.
     * 원하지 않는 것을 끼워 팔아 총액을 낮추면 절대 원칙 1(미사용 혜택은 0원)을 어긴다.
     */
    @Test
    void g25a_원하지_않는_등급이_섞인_번들은_쓰지_않는다() {
        var bundle = new BundleProduct(4, "티빙×웨이브 묶음", 15_000, Set.of(8L, 12L));

        // 티빙만 원한다 — 웨이브(12)는 원하지 않았다.
        var only = calculator.calculate(plan(List.of()), Set.of(T8), ctx(List.of(bundle)));

        assertThat(only.effectiveMonthlyCost()).isEqualTo(50_000 + 13_500);   // 번들가 15,000 이 아니다
        assertThat(only.lines()).noneMatch(l -> l.label().equals(bundle.name()));
    }

    /**
     * G-25b. 번들 가격이 개별 합계 <b>이상</b>이면 쓰지 않는다. 같을 때도 쓰지 않는다 —
     * 이득이 없는데 묶으면 나중에 하나를 해지할 자유만 잃는다.
     */
    @Test
    void g25b_개별합보다_비싸거나_같은_번들은_쓰지_않는다() {
        long individualSum = T8.listPrice() + T12.listPrice();       // 24,400
        var expensive = new BundleProduct(5, "비싼 묶음", individualSum + 1, Set.of(8L, 12L));
        var equal = new BundleProduct(6, "같은 값 묶음", individualSum, Set.of(8L, 12L));

        for (BundleProduct bundle : List.of(expensive, equal)) {
            var result = calculator.calculate(plan(List.of()), Set.of(T8, T12), ctx(List.of(bundle)));
            assertThat(result.effectiveMonthlyCost()).isEqualTo(50_000 + individualSum);
            assertThat(result.lines()).noneMatch(l -> l.label().equals(bundle.name()));
        }
    }

    /**
     * G-25c. 번들과 개별가를 비교할 때 <b>제휴 혜택이 적용된 개별가</b>를 쓴다.
     * 정가로 비교하면 이미 무료인 등급 때문에 번들이 유리해 보인다.
     */
    @Test
    void g25c_번들_비교는_혜택_적용_개별가로_한다() {
        // 티빙이 요금제 혜택으로 무료다 → 개별 합계는 웨이브 10,900 뿐이다.
        var freeTving = new PlanBenefit(TVING, 8L, BenefitType.FREE, null, false, null);
        var bundle = new BundleProduct(7, "티빙×웨이브 묶음", 12_000, Set.of(8L, 12L));

        var result = calculator.calculate(plan(List.of(freeTving)), Set.of(T8, T12), ctx(List.of(bundle)));

        // 번들 12,000 > 혜택 적용 개별합 10,900 이므로 번들을 쓰지 않는다.
        assertThat(result.lines()).noneMatch(l -> l.label().equals(bundle.name()));
        assertThat(result.effectiveMonthlyCost()).isEqualTo(50_000 + 10_900);
    }

    /* ── 혜택 유형 네 가지 (PlanBenefit.apply) ──────────────────────────────── */

    @Test
    void g25d_혜택_유형_네_가지가_각각_다른_금액을_만든다() {
        long list = 13_500;
        assertThat(benefit(BenefitType.FREE, null).apply(list)).isEqualTo(0L);
        assertThat(benefit(BenefitType.FIXED_DISCOUNT, BigDecimal.valueOf(4_000)).apply(list)).isEqualTo(9_500L);
        // 정률은 BigDecimal 로 계산하고 원 단위 내림 — 13,500 × 0.7 = 9,450
        assertThat(benefit(BenefitType.RATE_DISCOUNT, new BigDecimal("0.30")).apply(list)).isEqualTo(9_450L);
        // 번들에 포함된 등급은 여기서 깎지 않는다 — 번들 로직이 따로 값을 정한다.
        assertThat(benefit(BenefitType.BUNDLE_INCLUDED, null).apply(list)).isEqualTo(list);
    }

    /** G-25e. 정액 할인이 정가를 넘어도 음수가 되지 않는다(하한 0). */
    @Test
    void g25e_정액_할인은_0_아래로_내려가지_않는다() {
        assertThat(benefit(BenefitType.FIXED_DISCOUNT, BigDecimal.valueOf(20_000)).apply(13_500)).isZero();
    }

    /** G-25f. `tierId` 가 null 이면 그 서비스의 <b>모든 등급</b>에 걸린다. 있으면 그 등급에만 걸린다. */
    @Test
    void g25f_tierId가_없으면_서비스_전체에_걸린다() {
        var wholeService = new PlanBenefit(NETFLIX, null, BenefitType.FREE, null, false, null);
        var onlyTier2 = new PlanBenefit(NETFLIX, 2L, BenefitType.FREE, null, false, null);
        var otherTier = new SubscriptionTier(3, NETFLIX, "넷플릭스 프리미엄", 17_000);

        assertThat(wholeService.matches(T2)).isTrue();
        assertThat(wholeService.matches(otherTier)).isTrue();
        assertThat(onlyTier2.matches(T2)).isTrue();
        assertThat(onlyTier2.matches(otherTier)).isFalse();     // 등급이 지정되면 그 등급만
        assertThat(onlyTier2.matches(T8)).isFalse();            // 서비스가 다르면 어차피 아니다
    }

    private static PlanBenefit benefit(BenefitType type, BigDecimal value) {
        return new PlanBenefit(NETFLIX, 2L, type, value, false, null);
    }

    /* ── 값 객체·입력 검증 ──────────────────────────────────────────────────── */

    /** G-25g. 절감액은 음수로 표시하지 않는다 — "더 비싸진다"를 음수 절감으로 적으면 읽는 사람이 헷갈린다. */
    @Test
    void g25g_floorToZero는_음수를_0으로_만들고_양수는_그대로_둔다() {
        assertThat(Money.of(-1).floorToZero()).isEqualTo(Money.ZERO);
        assertThat(Money.of(0).floorToZero()).isEqualTo(Money.ZERO);
        assertThat(Money.of(1).floorToZero()).isEqualTo(Money.of(1));
    }

    /**
     * G-25h. 전환비용·약정 잔여는 음수일 수 없다. 조용히 0 으로 고치지 않고 거부한다 —
     * 음수가 들어왔다면 호출부가 무언가를 잘못 계산한 것이고, 그걸 삼키면 금액이 조용히 틀어진다.
     */
    @Test
    void g25h_음수_전환비용과_음수_약정잔여는_거부한다() {
        assertThatThrownBy(() -> SwitchTiming.evaluate(-1, 1_000, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SwitchTiming.evaluate(0, 1_000, -1))
                .isInstanceOf(IllegalArgumentException.class);
        // 경계: 0 은 정상이다.
        assertThat(SwitchTiming.evaluate(0, 1_000, 0).status()).isEqualTo(SwitchTiming.Status.SWITCH_NOW);
    }
}
