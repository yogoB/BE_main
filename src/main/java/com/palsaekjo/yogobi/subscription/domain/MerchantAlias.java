package com.palsaekjo.yogobi.subscription.domain;

import com.palsaekjo.yogobi.common.MatchType;

/** 가맹점 별칭 하나. 대소문자 무시로 매칭한다(라틴 표기 변형 흡수, 한글은 영향 없음). */
public record MerchantAlias(long serviceId, String pattern, MatchType matchType) {

    /** rawUpper 는 이미 대문자로 정규화된 가맹점 원문. */
    public boolean matches(String rawUpper) {
        String p = pattern.toUpperCase();
        return switch (matchType) {
            case CONTAINS -> rawUpper.contains(p);
            case PREFIX -> rawUpper.startsWith(p);
        };
    }
}
