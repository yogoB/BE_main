package com.palsaekjo.yogobi.pricing.domain;

import com.palsaekjo.yogobi.common.BenefitType;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** docs/domain.md §3: 혜택 형태는 코드 분기가 아니라 데이터로 표현한다. */
public record PlanBenefit(
        long serviceId,
        Long tierId,
        BenefitType benefitType,
        BigDecimal discountValue,
        boolean exclusive,
        String exclusiveGroup
) {
    public boolean matches(SubscriptionTier tier) {
        return serviceId == tier.serviceId() && (tierId == null || tierId == tier.id());
    }

    public long apply(long listPrice) {
        return switch (benefitType) {
            case FREE -> 0L;
            case FIXED_DISCOUNT -> Math.max(0L, listPrice - discountValue.longValueExact());
            case RATE_DISCOUNT -> BigDecimal.valueOf(listPrice)
                    .multiply(BigDecimal.ONE.subtract(discountValue))
                    .setScale(0, RoundingMode.FLOOR)
                    .longValueExact();
            case BUNDLE_INCLUDED -> listPrice;
        };
    }
}
