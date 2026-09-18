package com.palsaekjo.yogobi.recommend;

/**
 * 변경 시점 설명 포트(D-47). 구현은 내레이터 클라이언트다.
 * 판정과 숫자는 {@code pricing.SwitchTiming} 이 만들고, 여기서는 문구만 받는다.
 */
public interface SwitchTimingNarrator {

    /** {@code headline} 은 화면 배지, {@code note} 는 안내 줄이다. */
    record Wording(String headline, String note) {
    }

    /**
     * 내레이터가 닿지 않을 때 BE 가 쓰는 최소 문구. 판정은 우리가 아는 값이라 배지는 남기고
     * 설명만 비운다 — 화면이 "언제 옮겨야 하나"를 아예 못 말하면 이 화면의 쓸모가 사라진다.
     */
    static Wording fallback(String status) {
        return new Wording(switch (status) {
            case "SWITCH_NOW" -> "지금이 최적 실행 시점";
            case "WAIT_UNTIL_EXPIRY" -> "약정 만료 후가 이득";
            case "NO_BENEFIT" -> "절감 없음 · 참고용 일정";
            default -> status;
        }, "");
    }

    /** @param expiryDate 사용자가 적은 약정 만료일(`YYYY-MM-DD`). 모르면 null — 서버는 이 날짜를 모른다. */
    Wording explain(SwitchTimingService.Response timing, String expiryDate);
}
