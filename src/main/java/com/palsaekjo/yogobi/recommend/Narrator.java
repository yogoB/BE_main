package com.palsaekjo.yogobi.recommend;

import java.util.List;

/**
 * 추천 결과 사유(reasons) 큐레이션 포트. 구현은 AI 서버 게이트웨이(chat 모듈)다.
 * recommend → chat 순환을 피하려 의존을 역전한다(포트는 recommend, 구현은 chat).
 * AI 장애 시 빈 목록 — 사유는 보조 정보이며 결과·계산에는 영향이 없다(원칙 2·architecture.md §3).
 */
public interface Narrator {
    List<String> reasonsFor(CostResult result, List<MissingInput> missingInputs);
}
