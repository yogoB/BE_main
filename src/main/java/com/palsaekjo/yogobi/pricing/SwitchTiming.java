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
        NO_BENEFIT           // 월 절감액 <= 0, 또는 특가가 끝나 전환비용을 영영 회수하지 못함 → 바꿀 이유 없음
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
        return evaluate(switchingCost, monthlySavings, remainingContractMonths, null, null);
    }

    /**
     * 옮길 요금제가 기간 한정 특가면 그 뒤 달의 절감액이 다르다. 회수는 <b>달마다 쌓인 절감</b>으로 센다 —
     * 특가 동안의 월 절감으로만 나누면, 특가가 끝나 절감이 줄거나 사라지는 요금제를 "N개월이면 회수"라고
     * 말하게 된다(2026-10-01). 추천 쪽 {@code CostResult.periodSavings} 와 같은 두 구간 모델이다.
     *
     * <p>특가 안에 회수되지 않고 그 뒤 절감이 0 이하면 <b>영영 회수하지 못한다</b> → NO_BENEFIT.
     *
     * <p>ponytail: 옮길 쪽 특가만 본다. 지금 요금제의 특가가 언제 끝나는지는 가입일을 몰라 알 수 없다.
     * 특가 뒤가 더 싸지는 요금제(월 절감 ≤ 0 이다가 양수)도 NO_BENEFIT 으로 둔다 — 카탈로그에 아직 없다.
     *
     * @param promoMonths  옮길 요금제의 특가 개월 수. 특가가 아니거나 그 뒤 가격을 모르면 null
     * @param afterSavings 특가가 끝난 뒤 월 절감액(= 월절감액 - (정상가 - 특가)). promoMonths 와 같이 온다
     */
    public static Result evaluate(long switchingCost, long monthlySavings, int remainingContractMonths,
                                  Integer promoMonths, Long afterSavings) {
        if (switchingCost < 0 || remainingContractMonths < 0) {
            throw new IllegalArgumentException("switchingCost·remainingContractMonths 는 0 이상이어야 합니다.");
        }
        if (monthlySavings <= 0) {
            return new Result(switchingCost, monthlySavings, null, remainingContractMonths, Status.NO_BENEFIT);
        }
        int paybackMonths = Math.toIntExact(Math.ceilDiv(switchingCost, monthlySavings));
        if (promoMonths != null && afterSavings != null && paybackMonths > promoMonths) {
            long left = switchingCost - Math.multiplyExact((long) promoMonths, monthlySavings);
            if (afterSavings <= 0) {
                return new Result(switchingCost, monthlySavings, null, remainingContractMonths, Status.NO_BENEFIT);
            }
            paybackMonths = Math.addExact(promoMonths, Math.toIntExact(Math.ceilDiv(left, afterSavings)));
        }
        // 약정 잔여 0 = 기다릴 대상이 없다. 비교만 쓰면 "회수 4 < 잔여 0"이 거짓이라 끝난 약정을 기다리게 된다(G-11 g).
        Status status = remainingContractMonths == 0 || paybackMonths < remainingContractMonths
                ? Status.SWITCH_NOW : Status.WAIT_UNTIL_EXPIRY;
        return new Result(switchingCost, monthlySavings, paybackMonths, remainingContractMonths, status);
    }
}
