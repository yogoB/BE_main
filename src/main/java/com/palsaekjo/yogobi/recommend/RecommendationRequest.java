package com.palsaekjo.yogobi.recommend;

import java.util.List;

/** docs/architecture.md §3 요청 스키마. optional 필드는 없으면 null (missingInputs 로 안내). */
public record RecommendationRequest(Required required, Optional optional) {

    /**
     * {@code wantedTierIds} 는 선택이다. 사용자가 등급을 고른 서비스만 실으면 되고, 비우면 예전과 같이
     * 서버가 대표 등급(스탠다드 우선)을 고른다 — 이 필드를 안 보내던 호출은 그대로 동작한다.
     * 요청한 서비스에 속하지 않는 등급 id 는 무시한다.
     */
    public record Required(Integer monthlyDataGb, List<Long> wantedServiceIds, List<Long> wantedTierIds) {
    }

    /**
     * {@code familyBundleDiscountKrw} 는 <b>사용자가 확인해 적어 준 월 할인액</b>이다(G-28).
     * 통신사별 결합 할인표가 카탈로그에 없어 우리가 만들 수 없는 값이라 받아서 쓴다 — `USER_PROVIDED`.
     * {@code familyLineCount} 는 근거 문구용이며 <b>금액 계산에 넣지 않는다.</b>
     *
     * <p>{@code currentPlanId} 는 지금 쓰는 요금제다(G-30). 두 가지에 쓴다 —
     * 그 요금제의 실질월비용을 후보와 같은 규칙으로 계산해 응답의 {@code current} 에 싣고,
     * 그 통신사를 <b>현재 통신사로 확정</b>해 가족결합 할인을 어느 후보에 유지할지 정한다(G-29).
     * 카탈로그에 없는 id 면 막지 않고 안내만 남긴다.
     */
    public record Optional(String currentCarrier, String networkType,
                           String contractType, Boolean hasFamilyBundle,
                           Integer familyLineCount, Long familyBundleDiscountKrw,
                           Long currentPlanId) {
    }
}
