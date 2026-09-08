package com.palsaekjo.yogobi.pricing.domain;

public record CostLine(String label, ValuedAmount value, String note) {
    public CostLine(String label, ValuedAmount value) {
        this(label, value, null);
    }

    public long amount() {
        return value.amount().won();
    }
}
