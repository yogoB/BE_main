package com.palsaekjo.yogobi.pricing.domain;

import com.palsaekjo.yogobi.common.ContractType;
import java.util.List;

/**
 * pricing이 필요로 하는 외부 정보. hasFamilyBundle == null이면 사용자가 입력하지 않은 것이다
 * (docs/domain.md §9, missingInputs).
 */
public record PricingContext(
        ContractType contractType,
        Boolean hasFamilyBundle,
        long planContractDiscount,
        List<BundleProduct> availableBundles
) {
    public PricingContext withPlanContractDiscount(long discount) {
        return new PricingContext(contractType, hasFamilyBundle, discount, availableBundles);
    }
}
