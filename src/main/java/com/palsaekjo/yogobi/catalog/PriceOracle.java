package com.palsaekjo.yogobi.catalog;

import java.util.Optional;

/**
 * 공식 시세 조회 포트(D-29). 구현은 스마트초이스 어댑터(recommend 모듈)다.
 * catalog 는 하위 모듈이라 recommend 를 직접 의존할 수 없어 의존을 역전한다({@link Narrator} 와 같은 방식).
 * 확인하지 못하면 {@code empty} — "값이 틀렸다"가 아니라 "확인 못 했다"는 뜻이다.
 */
public interface PriceOracle {
    /** 통신사·요금제명이 공식 시세에 있으면 그 월정액(원). 없거나 조회 실패면 empty. */
    Optional<Long> officialPrice(String carrier, String planName, long dataMb, String networkType);
}
