package com.palsaekjo.yogobi.recommend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 한 조합(요금제 + 원하는 티어)의 실질월비용 내역. 추천/계산기 응답이 공유한다. */
@JsonIgnoreProperties(ignoreUnknown = true)   // 저장된 스냅숏에는 파생 필드(semiannualSavings)도 들어 있다
public record CostResult(
        long planId,
        String planName,
        String carrier,
        long monthlyTotal,
        long baseline,
        long monthlySavings,
        long annualSavings,
        List<BreakdownLine> breakdown,
        // 스마트초이스 공식 시세 대조 결과(D-20 1차 교차검증). **표시·신뢰용이며 금액·정렬에 넣지 않는다**(D-03).
        // 스냅샷에 없으면 UNVERIFIED — "틀렸다"가 아니라 "확인 못 했다"이다.
        PriceCrossCheck.Verdict priceCrossCheck
) {
    /** 대조 전 결과. 서비스가 {@link #withPriceCrossCheck} 로 채운다. */
    public CostResult(long planId, String planName, String carrier, long monthlyTotal, long baseline,
                      long monthlySavings, long annualSavings, List<BreakdownLine> breakdown) {
        this(planId, planName, carrier, monthlyTotal, baseline, monthlySavings, annualSavings, breakdown,
                PriceCrossCheck.Verdict.unverified());
    }

    /** 6개월 절감(결과 대시보드의 1·6·12개월 탭, D-51). 화면이 ×6 을 하지 않도록 여기서 준다(절대 원칙 2). */
    @JsonProperty("semiannualSavings")
    public long semiannualSavings() {
        return monthlySavings * 6;
    }

    public CostResult withPriceCrossCheck(PriceCrossCheck.Verdict verdict) {
        return new CostResult(planId, planName, carrier, monthlyTotal, baseline, monthlySavings,
                annualSavings, breakdown, verdict);
    }
}
