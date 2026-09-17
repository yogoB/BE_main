package com.palsaekjo.yogobi.pricing.rule;

import com.palsaekjo.yogobi.common.Provenance;
import com.palsaekjo.yogobi.pricing.domain.Money;
import com.palsaekjo.yogobi.pricing.domain.PricingContext;

/** docs/domain.md §4: priority()로 적용 순서를 고정한다. */
public interface DiscountRule {
    boolean applies(PricingContext ctx);

    Money apply(Money base, PricingContext ctx);

    int priority();

    String label();

    /**
     * 이 규칙이 만든 금액의 출처(절대 원칙 4). 기본은 우리가 계산한 값이라 {@code DERIVED} 다.
     * 사용자가 말해 준 금액을 그대로 쓰는 규칙만 이 값을 바꾼다 — 계산한 적 없는 값을
     * 계산값이라 적으면 근거를 펼쳤을 때 거짓말이 된다.
     */
    default Provenance provenance() {
        return Provenance.DERIVED;
    }
}
