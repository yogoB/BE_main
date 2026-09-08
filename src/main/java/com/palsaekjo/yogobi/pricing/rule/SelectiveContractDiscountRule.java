package com.palsaekjo.yogobi.pricing.rule;

import com.palsaekjo.yogobi.common.ContractType;
import com.palsaekjo.yogobi.pricing.domain.Money;
import com.palsaekjo.yogobi.pricing.domain.PricingContext;
import java.math.BigDecimal;

public class SelectiveContractDiscountRule implements DiscountRule {
    private static final BigDecimal RETAINED_RATE = new BigDecimal("0.75");

    @Override
    public boolean applies(PricingContext ctx) {
        return ctx.contractType() == ContractType.SELECTIVE_25;
    }

    @Override
    public Money apply(Money base, PricingContext ctx) {
        return base.floorRate(RETAINED_RATE);
    }

    @Override
    public int priority() {
        return 200;
    }

    @Override
    public String label() {
        return "선택약정 25% 할인";
    }
}
