package com.palsaekjo.yogobi.pricing.domain;

import java.util.Set;

public record BundleProduct(long id, String name, long price, Set<Long> tierIds) {
}
