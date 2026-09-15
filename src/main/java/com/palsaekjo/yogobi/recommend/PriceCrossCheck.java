package com.palsaekjo.yogobi.recommend;

/**
 * 추천 요금제의 시드 기본료를 스마트초이스 라이브 시세와 대조한 결과(교차검증). 매칭되는 스냅샷이 없으면 null.
 * 계산에는 쓰지 않는다 — 시드 값이 최신인지 확인용 표시. 금액 계산의 유일 기준은 여전히 시드 기반 {@code monthlyTotal}이다.
 */
public record PriceCrossCheck(
        long livePrice,      // 스마트초이스 정상가(무약정 기준)
        long seedPrice,      // 우리 시드 기본료
        boolean matches,     // 두 값이 같으면 true (다르면 시드 갱신 필요 신호)
        String source,       // "스마트초이스(KTOA)"
        String collectedAt   // 스냅샷 수집일
) {
}
