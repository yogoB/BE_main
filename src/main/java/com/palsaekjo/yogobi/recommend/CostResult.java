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
        Long regularPrice,
        /**
         * 특가가 끝나면 요금제 부분이 얼마나 달라지는가({@code regularPrice - basePrice}).
         * 구독 금액은 그대로이므로 이 값만 월액에 더하면 종료 후 금액이 된다. 응답에는 싣지 않는다 —
         * 화면은 {@code regularPrice} 를 보면 되고, 이건 기간 계산용 중간값이다.
         */
        @com.fasterxml.jackson.annotation.JsonIgnore Long planPriceDelta
) {
    /** 대조 전 결과. 서비스가 {@link #withPriceCrossCheck} 로 채운다. */
    public CostResult(long planId, String planName, String carrier, long monthlyTotal, long baseline,
                      long monthlySavings, Long annualSavings, List<BreakdownLine> breakdown) {
        this(planId, planName, carrier, monthlyTotal, baseline, monthlySavings, annualSavings, breakdown,
                PriceCrossCheck.Verdict.unverified(), null, null, null);
    }

    /**
     * 6개월 절감(결과 대시보드의 1·6·12개월 탭, D-51). 화면이 ×6 을 하지 않도록 여기서 준다(절대 원칙 2).
     * <b>{@code null} 이면 모른다</b> — 특가가 6개월 안에 끝나는데 그 뒤 금액이 카탈로그에 없다.
     */
    @JsonProperty("semiannualSavings")
    public Long semiannualSavings() {
        return periodSavings(6);
    }

    /**
     * {@code months} 개월 동안의 절감액. 기간 한정 특가를 <b>실제로 반영해</b> 계산한다.
     *
     * <p>특가가 기간 안에 끝나면 그 뒤 달은 다른 금액이다. 요금제 부분만 {@code regularPrice - basePrice}
     * 만큼 달라지고 구독 금액은 그대로이므로, 기간 총액은
     * {@code 특가개월 × 지금월액 + 남은개월 × (지금월액 + 차액)} 이다. 절감액은 정가 기준과의 차다.
     *
     * <p>이 계산이 없던 동안 안내 문구는 "이후에는 월 M원이라 <b>그만큼 반영해 계산했어요</b>" 라고
     * 말하면서 실제로는 월 × N 을 하고 있었다(2026-09-21, 그룹 B 검증 중 발견). <b>말한 것을 하지
     * 않는 쪽이 모른다고 말하는 것보다 나쁘다.</b>
     *
     * <p>특가가 <b>끝난 뒤 더 싸지는</b> 경우도 있다(장기할인). 부호를 가리지 않는다 — 그대로 더한다.
     */
    private Long periodSavings(int months) {
        if (promoMonths == null || promoMonths >= months) {
            return monthlySavings * months;                 // 기간 내내 같은 금액이다
        }
        if (regularPrice == null || planPriceDelta == null) {
            return null;                                    // 특가가 끝나는데 그 뒤를 모른다
        }
        long afterMonthlySavings = monthlySavings - planPriceDelta;
        return promoMonths * monthlySavings + (months - promoMonths) * afterMonthlySavings;
    }

    public CostResult withPriceCrossCheck(PriceCrossCheck.Verdict verdict) {
        return new CostResult(planId, planName, carrier, monthlyTotal, baseline, monthlySavings,
                annualSavings, breakdown, verdict, promoMonths, regularPrice, planPriceDelta);
    }

    /**
     * 특가 정보를 실어 새 결과를 만든다. 연·반기 절감액이 여기서 다시 계산된다 —
     * 특가가 기간 안에 끝나면 그 뒤 달을 다른 금액으로 더하고, 모르면 비운다.
     */
    public CostResult withPromotion(Integer months, Long regular, long basePrice) {
        Long delta = regular == null ? null : regular - basePrice;
        var withPromo = new CostResult(planId, planName, carrier, monthlyTotal, baseline, monthlySavings,
                annualSavings, breakdown, priceCrossCheck, months, regular, delta);
        return new CostResult(planId, planName, carrier, monthlyTotal, baseline, monthlySavings,
                withPromo.periodSavings(12), breakdown, priceCrossCheck, months, regular, delta);
    }
}
