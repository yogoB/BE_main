package com.palsaekjo.yogobi.pricing.rule;

import com.palsaekjo.yogobi.pricing.domain.Money;
import com.palsaekjo.yogobi.pricing.domain.PricingContext;

public class PlanContractDiscountRule implements DiscountRule {
    @Override
    public boolean applies(PricingContext ctx) {
        return ctx.planContractDiscount() > 0;
    }

    @Override
    public Money apply(Money base, PricingContext ctx) {
        return base.minus(Money.of(ctx.planContractDiscount())).floorToZero();
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public String label() {
        return "약정할인";
    }
}
