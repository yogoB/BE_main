package com.palsaekjo.yogobi.detection.domain;

import com.palsaekjo.yogobi.common.DetectionRule;
import com.palsaekjo.yogobi.common.Provenance;

/**
 * 탐지 결과 한 건. {@code wastedAmount} 는 월 단위 낭비 금액이다.
 *
 * <p>{@code provenance} 는 그 금액을 어디서 얻었는지다(절대 원칙 4).
 * <ul>
 *   <li>{@code DERIVED} — 카탈로그 정가·혜택으로 계산했다.
 *   <li>{@code ESTIMATED} — 요금제가 등급을 밝히지 않아 <b>상한</b>(사용자가 내는 금액 전액)으로 잡았다.
 *       <b>표시 전용</b>이며 추천 금액 계산에 넣지 않는다(D-17).
 * </ul>
 */
public record DetectionFinding(DetectionRule rule, String targetRef, long wastedAmount, Provenance provenance) {
    /** 카탈로그 값으로 계산한 금액. */
    public static DetectionFinding derived(DetectionRule rule, String targetRef, long wastedAmount) {
        return new DetectionFinding(rule, targetRef, wastedAmount, Provenance.DERIVED);
    }
}
