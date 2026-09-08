package com.palsaekjo.yogobi.pricing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 원 단위 금액. docs/domain.md: 정률 할인은 BigDecimal로 계산 후 내림. */
public record Money(long won) {
    public static final Money ZERO = new Money(0);

    public static Money of(long won) {
        return new Money(won);
    }

    public Money minus(Money other) {
        return new Money(won - other.won);
    }

    public Money floorToZero() {
        return won < 0 ? ZERO : this;
    }

    public Money floorRate(BigDecimal retainedRate) {
        long floored = BigDecimal.valueOf(won)
                .multiply(retainedRate)
                .setScale(0, RoundingMode.FLOOR)
                .longValueExact();
        return new Money(floored);
    }
}
