package com.palsaekjo.yogobi.pricing;

/**
 * docs/domain.md §8 변경 시점(회수기간). 순수 도메인 — Spring 의존 없음.
 * "변경 시점 추천"과 "프로모션 종료 알림"의 공통 엔진이다. 금액은 long 원 단위, 올림은 정수 연산(double 금지).
 */
public final class SwitchTiming {
    private SwitchTiming() {
    }

    public enum Status {
        SWITCH_NOW,          // 약정 잔여 0 또는 회수개월 < 약정 잔여 → 지금 바꾸는 게 이득
        WAIT_UNTIL_EXPIRY,   // 회수가 약정 잔여 이상 → 만료까지 기다림
        NO_BENEFIT           // 월 절감액 <= 0 → 바꿀 이유 없음
    }

    /** paybackMonths 는 NO_BENEFIT 이면 null. */
    public record Result(long switchingCost, long monthlySavings, Integer paybackMonths,
                         int remainingContractMonths, Status status) {
    }

    /**
     * @param switchingCost           전환비용 = 할인반환금 + 잔여 할부금 + 재약정 손실 (>= 0)
     * @param monthlySavings          월 절감액 = 현재 실질월비용 - 추천 조합 실질월비용
     * @param remainingContractMonths 약정 잔여 개월 (>= 0)
     */
    public static Result evaluate(long switchingCost, long monthlySavings, int remainingContractMonths) {
        if (switchingCost < 0 || remainingContractMonths < 0) {
            throw new IllegalArgumentException("switchingCost·remainingContractMonths 는 0 이상이어야 합니다.");
        }
        if (monthlySavings <= 0) {
            return new Result(switchingCost, monthlySavings, null, remainingContractMonths, Status.NO_BENEFIT);
        }
        int paybackMonths = Math.toIntExact(Math.ceilDiv(switchingCost, monthlySavings));
        // 약정 잔여 0 = 기다릴 대상이 없다. 비교만 쓰면 "회수 4 < 잔여 0"이 거짓이라 끝난 약정을 기다리게 된다(G-11 g).
        Status status = remainingContractMonths == 0 || paybackMonths < remainingContractMonths
                ? Status.SWITCH_NOW : Status.WAIT_UNTIL_EXPIRY;
        return new Result(switchingCost, monthlySavings, paybackMonths, remainingContractMonths, status);
    }
}
