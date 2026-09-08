package com.palsaekjo.yogobi.pricing.domain;

public record SubscriptionTier(long id, long serviceId, String name, long listPrice) {
}
