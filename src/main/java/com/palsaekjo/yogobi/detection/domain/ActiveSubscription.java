package com.palsaekjo.yogobi.detection.domain;

/**
 * 사용자가 지금 결제 중인 구독 한 건.
 *
 * @param monthlyAmount 사용자가 실제로 내는 금액(USER_PROVIDED)
 * @param listPrice     카탈로그 정가. 혜택 적용가와 비교해 낭비액을 구하는 기준이다 —
 *                      혜택은 정가에 걸리므로 사용자가 내는 금액으로 계산하면 할인이 두 번 먹는다.
 */
public record ActiveSubscription(long serviceId, Long tierId, String label, long monthlyAmount, long listPrice) {
}
