package com.palsaekjo.yogobi.detection;

import com.palsaekjo.yogobi.common.BenefitType;
import com.palsaekjo.yogobi.common.DetectionRule;
import com.palsaekjo.yogobi.common.Provenance;
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

    /**
     * 요금제가 이미 주는 서비스를 따로 결제 중이면 그 차액이 낭비다.
     *
     * <p>2026-09-17 이전에는 {@code FREE} 만 봤다. 운영 혜택 73행 중 {@code BUNDLE_INCLUDED} 가
     * 51행(70%)이라 <b>요금제 이름에 "넷플릭스 포함"이 박힌 회원의 중복 결제를 통째로 놓쳤다.</b>
     * 실제 그런 회원이 있었고 탐지가 침묵했다(G-09 e).
     */
    private List<DetectionFinding> benefitOverlaps(List<ActiveSubscription> active,
            List<PlanBenefit> planBenefits) {
        List<DetectionFinding> findings = new ArrayList<>();
        for (ActiveSubscription sub : active) {
            for (PlanBenefit benefit : planBenefits) {
                if (!covers(benefit, sub)) {
                    continue;
                }
                DetectionFinding finding = waste(benefit, sub);
                // 낭비가 없으면 다음 혜택을 계속 본다 — 0원짜리 할인이 전액 포함 혜택을 가리면 안 된다.
                if (finding != null) {
                    findings.add(finding);
                    break;
                }
            }
        }
        return findings;
    }

    /** 혜택이 이 구독에 걸리는가. 등급을 지정하지 않은 혜택은 그 서비스의 모든 등급에 걸린다. */
    private static boolean covers(PlanBenefit benefit, ActiveSubscription sub) {
        return benefit.serviceId() == sub.serviceId()
                && (benefit.tierId() == null || benefit.tierId().equals(sub.tierId()));
    }

    /**
     * 이 혜택을 쓰지 않아 버리는 금액. 없으면 {@code null}.
     *
     * <p>{@code BUNDLE_INCLUDED} 는 "요금제 가격에 이미 포함" 이라 <b>내는 금액 전액</b>이 낭비다.
     * 다만 등급을 밝히지 않은 혜택이면 얼마가 낭비인지 확정할 수 없다 — 전액일 수도, 상위 등급
     * 차액만일 수도 있다. 그때는 상한을 {@code ESTIMATED} 로 낸다(표시 전용, D-17).
     * 확정할 수 없다고 침묵하는 것보다 "확인해 보세요" 가 사용자에게 낫다.
     */
    private static DetectionFinding waste(PlanBenefit benefit, ActiveSubscription sub) {
        String ref = "service:" + sub.serviceId();
        if (benefit.benefitType() == BenefitType.BUNDLE_INCLUDED) {
            return new DetectionFinding(DetectionRule.BENEFIT_OVERLAP, ref, sub.monthlyAmount(),
                    benefit.tierId() == null ? Provenance.ESTIMATED : Provenance.DERIVED);
        }
        // 혜택은 카탈로그 정가에 걸린다. 사용자가 내는 금액으로 계산하면 할인이 두 번 먹는다.
        long wasted = sub.monthlyAmount() - benefit.apply(sub.listPrice());
        return wasted > 0 ? DetectionFinding.derived(DetectionRule.BENEFIT_OVERLAP, ref, wasted) : null;
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
            findings.add(DetectionFinding.derived(DetectionRule.TIER_DUPLICATE,
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
                findings.add(DetectionFinding.derived(DetectionRule.BUNDLE_OVERLAP,
                        "bundle:" + bundle.id(), individual - bundle.price()));
            }
        }
        return findings;
    }
}
