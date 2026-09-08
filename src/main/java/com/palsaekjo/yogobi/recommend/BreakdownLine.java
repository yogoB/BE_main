package com.palsaekjo.yogobi.recommend;

/** CostBreakdown 의 CostLine 을 API 응답 형태로 옮긴 것. amount 는 할인 시 음수. */
public record BreakdownLine(String label, long amount, String provenance, String note) {
}
