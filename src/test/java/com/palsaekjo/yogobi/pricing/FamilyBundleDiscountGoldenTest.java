package com.palsaekjo.yogobi.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.palsaekjo.yogobi.common.ContractType;
import com.palsaekjo.yogobi.common.Provenance;
import com.palsaekjo.yogobi.pricing.domain.CostLine;
import com.palsaekjo.yogobi.pricing.domain.MobilePlan;
import com.palsaekjo.yogobi.pricing.domain.PricingContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * docs/testing.md G-28 — 가족결합 할인은 사용자가 말해 준 금액이다.
 *
 * <p>카탈로그에 통신사별 결합 할인표가 없어 우리가 금액을 만들 수 없다. 사용자에게 받아 그대로 빼고,
 * 출처는 {@code USER_PROVIDED} 로 적는다. 여기서 지키는 것은 "우리가 지어내지 않는다" 와
 * "순서를 뒤집지 않는다" 둘이다.
 */
class FamilyBundleDiscountGoldenTest {

    private final CostCalculator calculator = new CostCalculator();

    /** 기준 요금제: 기본료 55,000원, 요금제 고유 약정할인 없음, 제휴 혜택 없음. */
    private static MobilePlan plan() {
        return new MobilePlan(99, "검증용", 55_000, 0, List.of());
    }

    private static PricingContext ctx(ContractType contract, Boolean bundled, Long discount) {
        return new PricingContext(contract, bundled, discount, 0, List.of());
    }

    private long telecomTotal(PricingContext ctx) {
        return calculator.calculate(plan(), Set.of(), ctx).effectiveMonthlyCost();
    }

    private CostLine familyLine(PricingContext ctx) {
        return calculator.calculate(plan(), Set.of(), ctx).lines().stream()
                .filter(line -> "가족결합 할인".equals(line.label()))
                .findFirst().orElse(null);
    }

    /** a — 결합 O · 할인 11,000 → 44,000. 출처는 USER_PROVIDED 다(우리가 계산한 값이 아니다). */
    @Test
    void a_userProvidedDiscountIsSubtractedAndLabelledAsTheirs() {
        var ctx = ctx(ContractType.NONE, true, 11_000L);
        assertThat(telecomTotal(ctx)).isEqualTo(44_000);
        CostLine line = familyLine(ctx);
        assertThat(line).isNotNull();
        assertThat(line.amount()).isEqualTo(-11_000);
        assertThat(line.value().provenance()).isEqualTo(Provenance.USER_PROVIDED);
    }

    /** b — 결합 O · 할인액 모름 → 그대로. 모르는 금액을 만들어 내지 않는다. */
    @Test
    void b_bundledButUnknownDiscountChangesNothing() {
        var ctx = ctx(ContractType.NONE, true, null);
        assertThat(telecomTotal(ctx)).isEqualTo(55_000);
        assertThat(familyLine(ctx)).isNull();
    }

    /** c — 결합 X 인데 금액이 왔다 → 무시. 결합하지 않은 사람에게 결합 할인을 주지 않는다. */
    @Test
    void c_discountIsIgnoredWhenNotBundled() {
        assertThat(telecomTotal(ctx(ContractType.NONE, false, 11_000L))).isEqualTo(55_000);
        assertThat(telecomTotal(ctx(ContractType.NONE, null, 11_000L))).isEqualTo(55_000);
    }

    /** d — 할인액이 요금보다 크다 → 0원. 가구 전체 할인액을 적는 사람이 있어도 음수 요금은 만들지 않는다. */
    @Test
    void d_discountNeverPushesTheBillBelowZero() {
        var ctx = ctx(ContractType.NONE, true, 60_000L);
        assertThat(telecomTotal(ctx)).isZero();
        assertThat(familyLine(ctx).amount()).isEqualTo(-55_000);
    }

    /**
     * e — 핵심. 선택약정 25% 와 함께면 55,000 ×0.75 = 41,250 → −11,000 = 30,250 이다.
     * 순서를 뒤집으면 (55,000 − 11,000) ×0.75 = 33,000 으로 달라진다.
     * docs/domain.md §4 가 선택약정 200 · 결합 300 으로 고정한 이유가 이것이다.
     */
    @Test
    void e_rateDiscountRunsBeforeTheFlatBundleDiscount() {
        assertThat(telecomTotal(ctx(ContractType.SELECTIVE_25, true, 11_000L))).isEqualTo(30_250);
    }

    /** f — 0원 할인은 적용하지 않는다. "결합은 하는데 할인은 0원" 을 할인 줄로 적으면 근거가 지저분해진다. */
    @Test
    void f_zeroDiscountAddsNoLine() {
        var ctx = ctx(ContractType.NONE, true, 0L);
        assertThat(telecomTotal(ctx)).isEqualTo(55_000);
        assertThat(familyLine(ctx)).isNull();
    }
}
