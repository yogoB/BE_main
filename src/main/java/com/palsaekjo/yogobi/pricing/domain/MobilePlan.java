package com.palsaekjo.yogobi.pricing.domain;

import java.util.List;

/**
 * 통신 요금제. {@code basePrice} 는 <b>지금(1개월차) 내는 금액</b>이다 — 기간 한정 특가가 걸려 있으면
 * 그 특가가일 수도, 정상가일 수도 있다. 어느 쪽인지는 {@code promoMonths}·{@code regularPrice} 가 말한다.
 *
 * @param promoMonths  특가가 유지되는 개월 수. {@code null} 이면 기간 한정 특가가 아니다.
 * @param regularPrice 특가 종료 후 월 요금. <b>{@code null} 이면 모른다는 뜻</b>이고, 그때는
 *                     기간 총액·절감액을 숫자로 내지 않는다. 추정값을 넣지 않는다(D-43).
 */
public record MobilePlan(
        long id,
        String name,
        long basePrice,
        long planContractDiscount,
        List<PlanBenefit> benefits,
        Integer promoMonths,
        Long regularPrice,
        /**
         * 조건을 채웠을 때의 월 요금. <b>계산에도 정렬에도 쓰지 않는다</b> — 조건 충족 여부를 우리가
         * 모르기 때문이다. 있다는 사실만 화면에 알린다(절대 원칙 1 과 같은 논리).
         */
        Long benefitPrice,
        /** 출처가 그 금액을 부르는 이름 그대로("최종 혜택가"·"최대 할인가"). 조건을 지어내지 않는다. */
        String benefitLabel
) {
    /** 특가가 없는 요금제. 테스트와 계산기 경로가 쓴다. */
    public MobilePlan(long id, String name, long basePrice, long planContractDiscount, List<PlanBenefit> benefits) {
        this(id, name, basePrice, planContractDiscount, benefits, null, null, null, null);
    }

    // 기간 판정은 여기 두지 않는다 — 응답을 만드는 CostResult 가 기간별로 따로 낸다(G-66).
    // pricing 은 순수 도메인이고 분기 커버리지 100%가 강제되므로, 안 쓰는 헬퍼를 두면 게이트가 먼저 잡는다.
}
