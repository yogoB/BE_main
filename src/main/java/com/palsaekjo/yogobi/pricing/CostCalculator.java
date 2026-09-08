package com.palsaekjo.yogobi.pricing;

import com.palsaekjo.yogobi.common.Accuracy;
import com.palsaekjo.yogobi.common.Provenance;
import com.palsaekjo.yogobi.pricing.domain.BundleProduct;
import com.palsaekjo.yogobi.pricing.domain.CostBreakdown;
import com.palsaekjo.yogobi.pricing.domain.CostLine;
import com.palsaekjo.yogobi.pricing.domain.MobilePlan;
import com.palsaekjo.yogobi.pricing.domain.Money;
import com.palsaekjo.yogobi.pricing.domain.PlanBenefit;
import com.palsaekjo.yogobi.pricing.domain.PricingContext;
import com.palsaekjo.yogobi.pricing.domain.SubscriptionTier;
import com.palsaekjo.yogobi.pricing.domain.ValuedAmount;
import com.palsaekjo.yogobi.pricing.rule.DiscountRule;
import com.palsaekjo.yogobi.pricing.rule.PlanContractDiscountRule;
import com.palsaekjo.yogobi.pricing.rule.SelectiveContractDiscountRule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** docs/domain.md §1 수식의 구현. Spring 의존 금지 — 순수 도메인. */
public final class CostCalculator {
    private static final List<DiscountRule> TELECOM_RULES = List.of(
                    new PlanContractDiscountRule(),
                    new SelectiveContractDiscountRule())
            .stream()
            .sorted(Comparator.comparingInt(DiscountRule::priority))
            .toList();

    public CostBreakdown calculate(MobilePlan plan, Set<SubscriptionTier> wanted, PricingContext ctx) {
        List<CostLine> lines = new ArrayList<>();
        lines.add(new CostLine(plan.name() + " 기본료",
                new ValuedAmount(Money.of(plan.basePrice()), Provenance.OFFICIAL)));

        PricingContext planCtx = ctx.withPlanContractDiscount(plan.planContractDiscount());
        Money telecom = Money.of(plan.basePrice());
        for (DiscountRule rule : TELECOM_RULES) {
            if (!rule.applies(planCtx)) {
                continue;
            }
            Money after = rule.apply(telecom, planCtx);
            lines.add(new CostLine(rule.label(),
                    new ValuedAmount(Money.of(after.won() - telecom.won()), Provenance.DERIVED)));
            telecom = after;
        }

        SubscriptionResult subscription = resolveSubscriptions(plan, wanted, ctx.availableBundles());
        lines.addAll(subscription.lines());

        long effectiveMonthlyCost = telecom.won() + subscription.total();
        long baseline = plan.basePrice()
                + wanted.stream().mapToLong(SubscriptionTier::listPrice).sum();
        long monthlySavings = baseline - effectiveMonthlyCost;

        boolean missingFamilyBundle = ctx.hasFamilyBundle() == null;
        Accuracy accuracy = missingFamilyBundle ? Accuracy.PARTIAL : Accuracy.FULL;
        List<String> missingInputs = missingFamilyBundle ? List.of("hasFamilyBundle") : List.of();

        return new CostBreakdown(lines, baseline, effectiveMonthlyCost, monthlySavings,
                monthlySavings * 12, accuracy, missingInputs);
    }

    private SubscriptionResult resolveSubscriptions(MobilePlan plan, Set<SubscriptionTier> wanted,
            List<BundleProduct> availableBundles) {
        Map<Long, SubscriptionTier> remaining = new LinkedHashMap<>();
        for (SubscriptionTier tier : wanted) {
            remaining.put(tier.id(), tier);
        }

        List<CostLine> lines = new ArrayList<>();
        long total = 0;

        // ponytail: 먼저 맞는 번들부터 첫 적용(first-fit). 겹치는 번들 간 전역 최적 조합은
        // 아직 계산하지 않는다 — N/|S| 규모가 작을 때 늘리면 됨(docs/domain.md §6).
        for (BundleProduct bundle : availableBundles) {
            if (!remaining.keySet().containsAll(bundle.tierIds())) {
                continue;
            }
            long individualSum = bundle.tierIds().stream()
                    .mapToLong(id -> individualCost(plan, remaining.get(id)))
                    .sum();
            if (bundle.price() >= individualSum) {
                continue;
            }
            lines.add(new CostLine(bundle.name(),
                    new ValuedAmount(Money.of(bundle.price()), Provenance.OFFICIAL)));
            total += bundle.price();
            bundle.tierIds().forEach(remaining::remove);
        }

        Set<Long> exclusiveWinnerTierIds = chooseExclusiveWinners(plan, remaining.values());
        for (SubscriptionTier tier : remaining.values()) {
            PlanBenefit benefit = findApplicableBenefit(plan, tier, exclusiveWinnerTierIds);
            long cost = benefit == null ? tier.listPrice() : benefit.apply(tier.listPrice());
            String note = benefit == null ? null : "제휴 혜택 적용";
            lines.add(new CostLine(tier.name(),
                    new ValuedAmount(Money.of(cost), Provenance.OFFICIAL), note));
            total += cost;
        }

        return new SubscriptionResult(lines, total);
    }

    /** 택1(exclusive) 그룹마다 원하는 서비스 중 절감액이 가장 큰 티어 하나만 승자로 고른다. */
    private Set<Long> chooseExclusiveWinners(MobilePlan plan, Iterable<SubscriptionTier> tiers) {
        Map<String, Long> bestSavingsByGroup = new HashMap<>();
        Map<String, Long> winnerTierIdByGroup = new HashMap<>();
        for (PlanBenefit benefit : plan.benefits()) {
            if (!benefit.exclusive()) {
                continue;
            }
            for (SubscriptionTier tier : tiers) {
                if (!benefit.matches(tier)) {
                    continue;
                }
                long savings = tier.listPrice() - benefit.apply(tier.listPrice());
                if (savings > bestSavingsByGroup.getOrDefault(benefit.exclusiveGroup(), Long.MIN_VALUE)) {
                    bestSavingsByGroup.put(benefit.exclusiveGroup(), savings);
                    winnerTierIdByGroup.put(benefit.exclusiveGroup(), tier.id());
                }
            }
        }
        return new HashSet<>(winnerTierIdByGroup.values());
    }

    private PlanBenefit findApplicableBenefit(MobilePlan plan, SubscriptionTier tier,
            Set<Long> exclusiveWinnerTierIds) {
        for (PlanBenefit benefit : plan.benefits()) {
            if (!benefit.matches(tier)) {
                continue;
            }
            if (benefit.exclusive() && !exclusiveWinnerTierIds.contains(tier.id())) {
                continue;
            }
            return benefit;
        }
        return null;
    }

    // ponytail: 번들 대비 개별가 비교용 — 택1 경쟁은 무시한다(번들+택1 동시 케이스는 골든 케이스 없음).
    private long individualCost(MobilePlan plan, SubscriptionTier tier) {
        for (PlanBenefit benefit : plan.benefits()) {
            if (benefit.matches(tier)) {
                return benefit.apply(tier.listPrice());
            }
        }
        return tier.listPrice();
    }

    private record SubscriptionResult(List<CostLine> lines, long total) {
    }
}
