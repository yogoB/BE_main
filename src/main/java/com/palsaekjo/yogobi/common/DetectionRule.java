package com.palsaekjo.yogobi.common;

/** 중복 결제 탐지 규칙 (docs/domain.md §7). */
public enum DetectionRule {
    BENEFIT_OVERLAP, TIER_DUPLICATE, BUNDLE_OVERLAP
}
