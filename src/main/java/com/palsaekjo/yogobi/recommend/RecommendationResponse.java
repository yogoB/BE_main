package com.palsaekjo.yogobi.recommend;

import com.palsaekjo.yogobi.common.Accuracy;
import java.util.List;

/** docs/architecture.md §3 응답 스키마. ApiResponse.data 에 실린다. */
public record RecommendationResponse(
        Accuracy accuracy,
        List<MissingInput> missingInputs,
        List<CostResult> results,
        /** 1순위에 대한 사유 0~3줄. 모델 또는 규칙이 만든다(D-38). AI 장애 시 빈 목록. */
        List<String> reasons,
        /** 1순위를 설명하는 문장. AI 서버의 결정론적 템플릿이라 모델 키가 없어도 나온다. 장애 시 null. */
        String message,
        /** 정렬 대상이 된 후보 요금제 수. results 에는 그중 상위 N개만 담긴다. 후보가 없으면 null. */
        Integer candidateCount
) {
    /** 후보를 찾지 못한 경로. 설명할 결과가 없다. */
    public RecommendationResponse(Accuracy accuracy, List<MissingInput> missingInputs, List<CostResult> results) {
        this(accuracy, missingInputs, results, List.of(), null, null);
    }

    /** 계산 경로는 설명 없이 만든다 — 컨트롤러가 채운다({@link #withNarration}). */
    public RecommendationResponse(Accuracy accuracy, List<MissingInput> missingInputs,
            List<CostResult> results, int candidateCount) {
        this(accuracy, missingInputs, results, List.of(), null, candidateCount);
    }

    public RecommendationResponse withNarration(Narrator.Narration narration) {
        return new RecommendationResponse(accuracy, missingInputs, results,
                narration.reasons(), narration.message(), candidateCount);
    }
}
