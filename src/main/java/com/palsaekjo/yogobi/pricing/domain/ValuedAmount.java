package com.palsaekjo.yogobi.pricing.domain;

import com.palsaekjo.yogobi.common.Provenance;

public record ValuedAmount(Money amount, Provenance provenance) {
}
