package com.palsaekjo.yogobi.recommend;

import com.palsaekjo.yogobi.common.Accuracy;
import java.util.List;

/** docs/architecture.md §3 응답 스키마. ApiResponse.data 에 실린다. */
public record RecommendationResponse(
        Accuracy accuracy,
        List<MissingInput> missingInputs,
        List<CostResult> results
) {
}
