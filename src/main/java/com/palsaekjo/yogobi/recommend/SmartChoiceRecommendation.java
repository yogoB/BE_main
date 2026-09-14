package com.palsaekjo.yogobi.recommend;

/**
 * 스마트초이스 Open API(KTOA)가 돌려주는 공식 추천 1건. 교차검증용이며 우리 카탈로그 요금제 ID·OTT 제휴 정보는 없다.
 * `rank`는 rn(으뜸1/알뜰2/넉넉3), 금액은 원 단위 정수. 출처 표기는 "스마트초이스(KTOA)".
 */
public record SmartChoiceRecommendation(
        int rank,
        String carrier,
        String planName,
        long planPrice,
        long discountedPrice,
        String displayData
) {
}
