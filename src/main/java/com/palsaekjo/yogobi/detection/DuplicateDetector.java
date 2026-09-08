package com.palsaekjo.yogobi.detection;

import com.palsaekjo.yogobi.common.BenefitType;
import com.palsaekjo.yogobi.common.DetectionRule;
import com.palsaekjo.yogobi.detection.domain.ActiveSubscription;
import com.palsaekjo.yogobi.detection.domain.DetectionFinding;
import com.palsaekjo.yogobi.pricing.domain.BundleProduct;
import com.palsaekjo.yogobi.pricing.domain.PlanBenefit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 중복 결제 탐지 (docs/domain.md §7). Spring 의존 없는 순수 도메인.
 * 세 규칙을 독립적으로 적용한다. 핵심: 혜택이 있어도 <b>다른 서비스</b>를 결제 중이면 중복이 아니다(G-09d).
 */
public final class DuplicateDetector {

    public List<DetectionFinding> detect(List<ActiveSubscription> active,
            List<PlanBenefit> planBenefits, List<BundleProduct> bundles) {
        List<DetectionFinding> findings = new ArrayList<>();
        findings.addAll(benefitOverlaps(active, planBenefits));
        findings.addAll(tierDuplicates(active));
        findings.addAll(bundleOverlaps(active, bundles));
        return findings;
    }

    /** 요금제 제휴로 무료인 서비스를 직접 결제 중이면 그 결제액이 낭비다. */
    private List<DetectionFinding> benefitOverlaps(List<ActiveSubscription> active,
            List<PlanBenefit> planBenefits) {
        List<DetectionFinding> findings = new ArrayList<>();
        for (ActiveSubscription sub : active) {
            for (PlanBenefit benefit : planBenefits) {
                boolean serviceMatch = benefit.serviceId() == sub.serviceId();
                boolean tierMatch = benefit.tierId() == null
                        || (sub.tierId() != null && benefit.tierId().equals(sub.tierId()));
                // ponytail: 무료 제공(FREE)만 본다. 정액/정률로 0원이 되는 경우는 골든 케이스 생기면 추가.
                if (serviceMatch && tierMatch && benefit.benefitType() == BenefitType.FREE) {
                    findings.add(new DetectionFinding(DetectionRule.BENEFIT_OVERLAP,
                            "service:" + sub.serviceId(), sub.monthlyAmount()));
                    break;
                }
            }
        }
        return findings;
    }

    /** 같은 서비스를 두 티어로 결제하면, 더 비싼 하나만 남기고 나머지가 낭비다. */
    private List<DetectionFinding> tierDuplicates(List<ActiveSubscription> active) {
        Map<Long, List<ActiveSubscription>> byService = new HashMap<>();
        for (ActiveSubscription sub : active) {
            byService.computeIfAbsent(sub.serviceId(), k -> new ArrayList<>()).add(sub);
        }
        List<DetectionFinding> findings = new ArrayList<>();
        for (var entry : byService.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            long total = entry.getValue().stream().mapToLong(ActiveSubscription::monthlyAmount).sum();
            long keep = entry.getValue().stream().mapToLong(ActiveSubscription::monthlyAmount).max().orElse(0);
            findings.add(new DetectionFinding(DetectionRule.TIER_DUPLICATE,
                    "service:" + entry.getKey(), total - keep));
        }
        return findings;
    }

    /** 번들 구성 서비스를 개별로 결제 중이고 개별 합이 번들가보다 비싸면 그 차액이 낭비다. */
    private List<DetectionFinding> bundleOverlaps(List<ActiveSubscription> active,
            List<BundleProduct> bundles) {
        Map<Long, ActiveSubscription> byTier = new HashMap<>();
        for (ActiveSubscription sub : active) {
            if (sub.tierId() != null) {
                byTier.putIfAbsent(sub.tierId(), sub);
            }
        }
        List<DetectionFinding> findings = new ArrayList<>();
        for (BundleProduct bundle : bundles) {
            if (!byTier.keySet().containsAll(bundle.tierIds())) {
                continue;
            }
            long individual = bundle.tierIds().stream()
                    .mapToLong(t -> byTier.get(t).monthlyAmount()).sum();
            if (bundle.price() < individual) {
                findings.add(new DetectionFinding(DetectionRule.BUNDLE_OVERLAP,
                        "bundle:" + bundle.id(), individual - bundle.price()));
            }
        }
        return findings;
    }
}
