package com.palsaekjo.yogobi.detection;

import com.palsaekjo.yogobi.detection.domain.DetectionFinding;
import java.util.List;

/**
 * 탐지 결과 설명 포트(D-46). 구현은 내레이터 클라이언트다.
 * 규칙 문구가 화면에 하드코딩돼 있으면 탐지 규칙이 늘 때마다 화면을 고쳐야 한다.
 *
 * <p>대상 이름은 <b>BE 가 카탈로그에서 찾아 넘긴다</b> — 내레이터는 카탈로그를 모른다.
 * 금액은 넘기기만 하고 내레이터가 표기만 바꾼다(절대 원칙 2).
 */
public interface DetectionNarrator {

    /**
     * 규칙별 제목과 방법 문장. 내레이터(`AI-/app/detections.py` RULES)와 <b>같은 문구</b>다 — 내레이터가 닿지 않을 때
     * 규칙 코드(BENEFIT_OVERLAP)가 화면에 그대로 뜨고 있었다(G-78 d). 문구를 바꾸면 양쪽을 같이 바꾼다.
     */
    java.util.Map<com.palsaekjo.yogobi.common.DetectionRule, String[]> WORDS = java.util.Map.of(
            com.palsaekjo.yogobi.common.DetectionRule.BENEFIT_OVERLAP, new String[] {
                    "요금제에 포함된 구독을 따로 결제 중", "요금제 혜택으로 이미 제공돼요. 개별 결제를 해지하면 그만큼 줄어요."},
            com.palsaekjo.yogobi.common.DetectionRule.TIER_DUPLICATE, new String[] {
                    "같은 서비스를 두 등급으로 결제 중", "더 비싼 등급 하나만 남기면 싼 등급 결제만큼 줄어요."},
            com.palsaekjo.yogobi.common.DetectionRule.BUNDLE_OVERLAP, new String[] {
                    "묶음 상품이 더 싼 조합", "개별 결제 합계가 묶음 상품보다 비싸요. 묶음으로 바꾸면 그만큼 줄어요."});

    /** 한 줄. {@code amount} 는 이미 표기까지 끝난 문자열이다("월 13,500원" · "최대 월 13,500원"). */
    record Explained(String title, String target, String amount, String how) {
    }

    record Explanation(List<Explained> lines, String summary) {
    }

    /**
     * 내레이터가 닿지 않을 때 BE 가 만드는 최소 설명. 화면이 통째로 비면 안 된다 —
     * 금액과 대상은 우리가 이미 아는 값이므로 문구만 없이 보여준다.
     */
    static Explanation fallback(List<DetectionFinding> findings, List<String> targetNames) {
        var lines = new java.util.ArrayList<Explained>();
        for (int i = 0; i < findings.size(); i++) {
            DetectionFinding finding = findings.get(i);
            String amount = "월 " + String.format("%,d", finding.wastedAmount()) + "원";
            // 상한으로 잡은 금액은 단정하지 않는다 — 내레이터와 같은 규칙(G-78 d).
            if (finding.provenance() == com.palsaekjo.yogobi.common.Provenance.ESTIMATED) amount = "최대 " + amount;
            // 규칙이 늘었는데 문구가 아직 없으면 NPE 대신 일반 제목으로 보여 준다 — 규칙 코드는 화면에 내지 않는다.
            String[] words = WORDS.getOrDefault(finding.rule(), new String[] {"겹치는 결제", ""});
            lines.add(new Explained(words[0], targetNames.get(i), amount, words[1]));
        }
        return new Explanation(List.copyOf(lines), "");
    }

    Explanation explain(List<DetectionFinding> findings, List<String> targetNames);
}
