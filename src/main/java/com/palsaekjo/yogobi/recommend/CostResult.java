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
        /**
         * 연 절감액. <b>{@code null} 이면 "모른다"</b>이지 0 이 아니다 — 기간 한정 특가가 12개월 안에
         * 끝나는데 그 뒤 금액을 카탈로그가 모를 때 그렇다(G-66). 0 을 주면 "안 아낀다"는 거짓말이 된다.
         */
        Long annualSavings,
        List<BreakdownLine> breakdown,
        // 스마트초이스 공식 시세 대조 결과(D-20 1차 교차검증). **표시·신뢰용이며 금액·정렬에 넣지 않는다**(D-03).
        // 스냅샷에 없으면 UNVERIFIED — "틀렸다"가 아니라 "확인 못 했다"이다.
        PriceCrossCheck.Verdict priceCrossCheck,
        /** 기간 한정 특가가 유지되는 개월 수. {@code null} 이면 특가가 아니다(G-66). */
        Integer promoMonths,
        /** 특가 종료 후 월 요금. {@code null} 이면 <b>확인하지 못했다</b> — 추정값을 넣지 않는다. */
        Long regularPrice
) {
    /** 대조 전 결과. 서비스가 {@link #withPriceCrossCheck} 로 채운다. */
    public CostResult(long planId, String planName, String carrier, long monthlyTotal, long baseline,
                      long monthlySavings, Long annualSavings, List<BreakdownLine> breakdown) {
        this(planId, planName, carrier, monthlyTotal, baseline, monthlySavings, annualSavings, breakdown,
                PriceCrossCheck.Verdict.unverified(), null, null);
    }

    /**
     * 6개월 절감(결과 대시보드의 1·6·12개월 탭, D-51). 화면이 ×6 을 하지 않도록 여기서 준다(절대 원칙 2).
     *
     * <p><b>{@code null} 이면 모른다</b>: 특가가 6개월 안에 끝나는데 그 뒤 금액이 카탈로그에 없다.
     * 연 절감액이 이미 모르는 값이면 6개월도 같은 이유로 모를 수 있으므로 기간별로 따로 판정한다 —
     * 7개월 특가는 6개월은 알고 12개월은 모른다.
     */
    @JsonProperty("semiannualSavings")
    public Long semiannualSavings() {
        return periodUnknown(6) ? null : monthlySavings * 6;
    }

    /** 이 기간의 금액을 확정할 수 없는가. 특가가 기간 안에 끝나는데 그 뒤 금액을 모를 때만 참이다. */
    private boolean periodUnknown(int months) {
        return promoMonths != null && promoMonths < months && regularPrice == null;
    }

    public CostResult withPriceCrossCheck(PriceCrossCheck.Verdict verdict) {
        return new CostResult(planId, planName, carrier, monthlyTotal, baseline, monthlySavings,
                annualSavings, breakdown, verdict, promoMonths, regularPrice);
    }

    /** 특가 정보를 실어 새 결과를 만든다. 기간 절감액 판정이 여기에 달려 있다. */
    public CostResult withPromotion(Integer months, Long regular) {
        return new CostResult(planId, planName, carrier, monthlyTotal, baseline, monthlySavings,
                months != null && months < 12 && regular == null ? null : annualSavings,
                breakdown, priceCrossCheck, months, regular);
    }
}
