package com.palsaekjo.yogobi.detection.domain;

import com.palsaekjo.yogobi.common.DetectionRule;

/** 탐지 결과 한 건. wastedAmount 는 월 단위 낭비 금액이다. */
public record DetectionFinding(DetectionRule rule, String targetRef, long wastedAmount) {
}
