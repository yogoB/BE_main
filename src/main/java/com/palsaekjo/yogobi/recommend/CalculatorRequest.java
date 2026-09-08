package com.palsaekjo.yogobi.recommend;

import java.util.List;

/** 특정 조합 총비용 요청. 추천과 달리 티어를 직접 지정한다 (대표 티어 휴리스틱 없음). */
public record CalculatorRequest(Long planId, List<Long> tierIds, RecommendationRequest.Optional optional) {
}
