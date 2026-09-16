package com.palsaekjo.yogobi.catalog;

import java.util.Optional;

/**
 * 공식 시세 조회 포트(D-29). 구현은 recommend 모듈의 어댑터들이다 — 스마트초이스, 통신사 공식 목록.
 * catalog 는 하위 모듈이라 recommend 를 직접 의존할 수 없어 의존을 역전한다({@link Narrator} 와 같은 방식).
 * 확인하지 못하면 {@code empty} — "값이 틀렸다"가 아니라 "확인 못 했다"는 뜻이다.
 *
 * <p>구현이 여럿이면 전부 물어본다. 소스가 늘수록 UNVERIFIED 가 줄고, 한 곳이라도 다른 금액을
 * 말하면 MISMATCH 다 — 늘린다고 통과가 쉬워지지 않는다.
 */
public interface PriceOracle {
    /** 통신사·요금제명이 공식 시세에 있으면 그 월정액(원). 없거나 조회 실패면 empty. */
    Optional<Long> officialPrice(String carrier, String planName, long dataMb, String networkType);

    /** 운영자가 읽을 소스 이름. 어느 곳이 무엇을 말했는지 구분되지 않으면 검토 근거를 읽을 수 없다. */
    default String sourceName() {
        return "공식 시세";
    }
}
