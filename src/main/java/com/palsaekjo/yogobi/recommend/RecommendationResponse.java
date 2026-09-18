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
        Integer candidateCount,
        /** 결과 화면 상단 ⓘ 안내 0~10줄. 내레이터가 missingInputs 로 만든다(D-46). 장애 시 빈 목록. */
        List<String> notices,
        /** 지금 쓰는 요금제의 금액. {@code optional.currentPlanId} 를 줬고 카탈로그에 있을 때만(G-30). */
        CurrentCost current,
        /**
         * 번호이동 없이 요금제만 바꾸는 선택지 — <b>지금 통신사 안에서 가장 싼 조합</b>(D-55).
         * 현재 통신사를 모르거나 그 통신사에 후보가 없으면 null. 전체 1순위와 같을 수 있다
         * (지금 통신사가 이미 가장 싸다는 뜻이고, 그것도 답이다).
         */
        CostResult minimalChange
) {
    /**
     * 지금 쓰는 요금제로 <b>같은 구독을 유지했을 때</b>의 실질월비용과, 1순위 추천 대비 절감액.
     * 후보와 같은 계산기·같은 컨텍스트로 낸 값이라 나란히 놓고 빼도 되는 두 금액이다(G-30).
     *
     * <p>절감액을 화면이 빼지 않고 여기서 주는 이유는 절대 원칙 2 다 — 금액은 BE 만 만든다.
     * 현재 요금제가 더 싸면 <b>음수 그대로</b> 나간다.
     */
    public record CurrentCost(CostResult cost, long monthlySavings, long annualSavings) {
        /** 6개월 절감(D-51). 음수면 음수 그대로. */
        @com.fasterxml.jackson.annotation.JsonProperty("semiannualSavings")
        public long semiannualSavings() {
            return monthlySavings * 6;
        }
    }

    /** 후보를 찾지 못한 경로. 설명할 결과가 없다. */
    public RecommendationResponse(Accuracy accuracy, List<MissingInput> missingInputs, List<CostResult> results) {
        this(accuracy, missingInputs, results, List.of(), null, null, List.of(), null, null);
    }

    /** 계산 경로는 설명 없이 만든다 — 컨트롤러가 채운다({@link #withNarration}). */
    public RecommendationResponse(Accuracy accuracy, List<MissingInput> missingInputs,
            List<CostResult> results, int candidateCount, CurrentCost current, CostResult minimalChange) {
        this(accuracy, missingInputs, results, List.of(), null, candidateCount, List.of(), current, minimalChange);
    }

    public RecommendationResponse withNarration(Narrator.Narration narration) {
        return new RecommendationResponse(accuracy, missingInputs, results,
                narration.reasons(), narration.message(), candidateCount, narration.notices(), current, minimalChange);
    }
}
