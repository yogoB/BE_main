package com.palsaekjo.yogobi.pricing.domain;

import java.util.List;

public record MobilePlan(
        long id,
        String name,
        long basePrice,
        long planContractDiscount,
        List<PlanBenefit> benefits
) {
}
