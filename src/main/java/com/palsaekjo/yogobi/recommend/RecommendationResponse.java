package com.palsaekjo.yogobi.recommend;

import com.palsaekjo.yogobi.common.Accuracy;
import java.util.List;

/** docs/architecture.md §3 응답 스키마. ApiResponse.data 에 실린다. */
public record RecommendationResponse(
        Accuracy accuracy,
        List<MissingInput> missingInputs,
        List<CostResult> results,
        List<String> reasons // 추천 1순위에 대한 AI 큐레이션 사유(0~3줄). AI 장애 시 빈 목록. D-19.
) {
    /** 계산 경로는 사유 없이 만든다 — 컨트롤러가 AI 큐레이션으로 채운다({@link #withReasons}). */
    public RecommendationResponse(Accuracy accuracy, List<MissingInput> missingInputs, List<CostResult> results) {
        this(accuracy, missingInputs, results, List.of());
    }

    public RecommendationResponse withReasons(List<String> reasons) {
        return new RecommendationResponse(accuracy, missingInputs, results, reasons);
    }
}
