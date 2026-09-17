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
            lines.add(new Explained(finding.rule().name(), targetNames.get(i), amount, ""));
        }
        return new Explanation(List.copyOf(lines), "");
    }

    Explanation explain(List<DetectionFinding> findings, List<String> targetNames);
}
