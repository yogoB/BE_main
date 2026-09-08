package com.palsaekjo.yogobi.pricing.rule;

import com.palsaekjo.yogobi.pricing.domain.Money;
import com.palsaekjo.yogobi.pricing.domain.PricingContext;

/** docs/domain.md §4: priority()로 적용 순서를 고정한다. */
public interface DiscountRule {
    boolean applies(PricingContext ctx);

    Money apply(Money base, PricingContext ctx);

    int priority();

    String label();
}
