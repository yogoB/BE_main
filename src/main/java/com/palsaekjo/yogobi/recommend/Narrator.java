package com.palsaekjo.yogobi.recommend;

import java.util.List;

/**
 * 추천 결과 설명 포트. 구현은 AI 서버 게이트웨이(chat 모듈)다.
 * recommend → chat 순환을 피하려 의존을 역전한다(포트는 recommend, 구현은 chat).
 * 설명은 보조 정보이며 결과·계산에는 영향이 없다(원칙 2·architecture.md §3).
 */
public interface Narrator {

    /**
     * 1순위 조합에 대한 설명. {@code message} 는 AI 서버의 <b>결정론적 템플릿</b>이고
     * {@code reasons} 는 모델 또는 규칙이 만든 0~3줄이다(D-38).
     * <b>둘 다 모델 API 키 없이 나온다</b> — 키는 사유 표현의 품질 등급일 뿐이다.
     */
    record Narration(String message, List<String> reasons) {
        public static Narration none() {
            return new Narration(null, List.of());
        }
    }

    /**
     * AI 장애는 {@link Narration#none()} 으로 흡수한다 — 화면은 금액만으로도 완결된다.
     *
     * @param candidateCount 정렬 대상이 된 후보 요금제 수. 혜택도 할인도 없는 요금제
     *                       (기준 카탈로그의 96%)에는 이 값이 "왜 추천됐나"의 유일한 근거다.
     */
    Narration narrationFor(CostResult result, List<MissingInput> missingInputs, Integer candidateCount);
}
