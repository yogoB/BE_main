package com.palsaekjo.yogobi.detection.domain;

/** 사용자가 현재 결제 중인 구독 하나. 결제내역·내 구독에서 만들어 탐지기에 넘긴다. */
public record ActiveSubscription(long serviceId, Long tierId, String label, long monthlyAmount) {
}
