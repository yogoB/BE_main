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
     */
    public record Optional(String currentCarrier, String networkType,
                           String contractType, Boolean hasFamilyBundle,
                           Integer familyLineCount, Long familyBundleDiscountKrw) {
    }
}
