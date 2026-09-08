package com.palsaekjo.yogobi.recommend;

import java.util.List;

/** docs/architecture.md §3 요청 스키마. optional 필드는 없으면 null (missingInputs 로 안내). */
public record RecommendationRequest(Required required, Optional optional) {

    public record Required(Integer monthlyDataGb, List<Long> wantedServiceIds) {
    }

    public record Optional(String currentCarrier, String networkType,
                           String contractType, Boolean hasFamilyBundle) {
    }
}
