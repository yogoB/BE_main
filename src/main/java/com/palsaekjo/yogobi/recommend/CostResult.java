package com.palsaekjo.yogobi.recommend;

import java.util.List;

/** 한 조합(요금제 + 원하는 티어)의 실질월비용 내역. 추천/계산기 응답이 공유한다. */
public record CostResult(
        long planId,
        String planName,
        String carrier,
        long monthlyTotal,
        long baseline,
        long monthlySavings,
        long annualSavings,
        List<BreakdownLine> breakdown
) {
}
