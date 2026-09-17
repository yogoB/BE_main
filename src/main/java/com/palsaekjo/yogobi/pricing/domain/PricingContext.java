package com.palsaekjo.yogobi.pricing.domain;

import com.palsaekjo.yogobi.common.ContractType;
import java.util.List;

/**
 * pricing이 필요로 하는 외부 정보. hasFamilyBundle == null이면 사용자가 입력하지 않은 것이다
 * (docs/domain.md §9, missingInputs).
 *
 * <p>{@code familyBundleDiscountKrw} 는 <b>사용자가 말해 준 월 할인액</b>이다. 카탈로그에 통신사별
 * 결합 할인표가 없어 우리가 만들 수 없는 값이라, 해외 결제 구독과 같은 방법으로 사용자에게 받는다
 * (G-28). null 이면 모르는 것이고 그때는 아무것도 깎지 않는다 — 지어내지 않는다.
 *
 * <p>결합 <b>회선 수는 여기 없다.</b> 금액 계산에 쓰지 않기 때문이다(G-28 f). 근거 문구에만 쓰므로
 * 응답 계층이 들고 있는다. pricing 은 금액에 쓰이는 것만 받는다.
 */
public record PricingContext(
        ContractType contractType,
        Boolean hasFamilyBundle,
        Long familyBundleDiscountKrw,
        long planContractDiscount,
        List<BundleProduct> availableBundles
) {
    /** 결합 할인액을 모르는 경우(대부분의 호출). 결합 할인은 적용되지 않는다. */
    public PricingContext(ContractType contractType, Boolean hasFamilyBundle,
                          long planContractDiscount, List<BundleProduct> availableBundles) {
        this(contractType, hasFamilyBundle, null, planContractDiscount, availableBundles);
    }

    public PricingContext withPlanContractDiscount(long discount) {
        return new PricingContext(contractType, hasFamilyBundle, familyBundleDiscountKrw, discount, availableBundles);
    }
}
