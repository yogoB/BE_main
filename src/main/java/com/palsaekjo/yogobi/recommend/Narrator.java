package com.palsaekjo.yogobi.recommend;

import java.util.List;

/**
 * 추천 결과 설명 포트. 구현은 내레이터 클라이언트({@link NarratorClient})다.
 * 설명은 보조 정보이며 결과·계산에는 영향이 없다(원칙 2·architecture.md §3).
 */
public interface Narrator {

    /**
     * 1순위 조합에 대한 설명. 셋 다 내레이터가 규칙으로 만든다 — 모델을 쓰지 않는다(D-45).
     * {@code message} 는 금액 문장, {@code reasons} 는 "왜 추천됐나" 0~3줄(D-38),
     * {@code notices} 는 화면 상단 ⓘ 안내다(D-46).
     */
    record Narration(String message, List<String> reasons, List<String> notices) {
        public static Narration none() {
            return new Narration(null, List.of(), List.of());
        }
    }

    /**
     * 내레이터 장애는 {@link Narration#none()} 으로 흡수한다 — 화면은 금액만으로도 완결된다.
     *
     * @param candidateCount 정렬 대상이 된 후보 요금제 수. 혜택도 할인도 없는 요금제
     *                       (기준 카탈로그의 96%)에는 이 값이 "왜 추천됐나"의 유일한 근거다.
     */
    Narration narrationFor(CostResult result, List<MissingInput> missingInputs, Integer candidateCount);
}
