package com.palsaekjo.yogobi.pricing.rule;

import com.palsaekjo.yogobi.common.Provenance;
import com.palsaekjo.yogobi.pricing.domain.Money;
import com.palsaekjo.yogobi.pricing.domain.PricingContext;

/**
 * 가족결합 할인(docs/domain.md §4 priority 300). <b>금액은 우리가 만들지 않고 사용자에게 받는다</b> —
 * 카탈로그에 통신사별 결합 할인표가 없기 때문이다(G-28).
 *
 * <p>결합 중이라고 했더라도 할인액을 모르면 아무것도 깎지 않는다. 결합 중이 아니면 금액을 보냈어도 무시한다.
 * 할인액이 남은 요금보다 크면 0원까지만 깎는다 — 가구 전체 할인액을 적는 사람이 있는데,
 * 그렇다고 요금을 음수로 만들 수는 없다.
 */
public class FamilyBundleDiscountRule implements DiscountRule {

    @Override
    public boolean applies(PricingContext ctx) {
        return Boolean.TRUE.equals(ctx.hasFamilyBundle())
                && ctx.familyBundleDiscountKrw() != null
                && ctx.familyBundleDiscountKrw() > 0;
    }

    @Override
    public Money apply(Money base, PricingContext ctx) {
        return base.minus(Money.of(ctx.familyBundleDiscountKrw())).floorToZero();
    }

    @Override
    public int priority() {
        return 300;
    }

    @Override
    public String label() {
        return "가족결합 할인";
    }

    /** 사용자가 확인해 적어 준 금액이다. 우리가 계산한 값이 아니다. */
    @Override
    public Provenance provenance() {
        return Provenance.USER_PROVIDED;
    }
}
