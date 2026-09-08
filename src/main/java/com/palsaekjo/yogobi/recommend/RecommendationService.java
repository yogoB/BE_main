package com.palsaekjo.yogobi.recommend;

import com.palsaekjo.yogobi.catalog.CatalogReader;
import com.palsaekjo.yogobi.catalog.CatalogReader.CandidatePlan;
import com.palsaekjo.yogobi.common.Accuracy;
import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.common.ContractType;
import com.palsaekjo.yogobi.pricing.CostCalculator;
import com.palsaekjo.yogobi.pricing.domain.BundleProduct;
import com.palsaekjo.yogobi.pricing.domain.CostBreakdown;
import com.palsaekjo.yogobi.pricing.domain.CostLine;
import com.palsaekjo.yogobi.pricing.domain.PricingContext;
import com.palsaekjo.yogobi.pricing.domain.SubscriptionTier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 필터·챗봇 공용 추천 엔진. 후보 요금제마다 실질월비용을 1회 계산해 싼 순으로 상위 N개를 낸다
 * (docs/domain.md §6). 금액은 만들지 않고 pricing 에 위임한다.
 */
@Service
public class RecommendationService {
    private static final int TOP_N = 5;
    private static final int MB_PER_GB = 1024;

    private final CatalogReader catalog;
    private final CostCalculator calculator = new CostCalculator();

    public RecommendationService(CatalogReader catalog) {
        this.catalog = catalog;
    }

    public RecommendationResponse recommend(RecommendationRequest request) {
        var required = request.required();
        if (required == null || required.monthlyDataGb() == null || required.monthlyDataGb() <= 0) {
            throw ApiException.requiredMissing("monthlyDataGb", "월 데이터 사용량(GB)이 필요합니다.");
        }
        if (required.wantedServiceIds() == null || required.wantedServiceIds().isEmpty()) {
            throw ApiException.requiredMissing("wantedServiceIds", "원하는 서비스를 하나 이상 선택하세요.");
        }

        List<SubscriptionTier> tiers = catalog.findRepresentativeTiers(required.wantedServiceIds());
        if (tiers.size() != distinct(required.wantedServiceIds())) {
            throw ApiException.requiredMissing("wantedServiceIds", "존재하지 않는 서비스 ID가 포함됐습니다.");
        }
        Set<SubscriptionTier> wanted = new LinkedHashSet<>(tiers);
        Set<Long> wantedTierIds = new LinkedHashSet<>(tiers.stream().map(SubscriptionTier::id).toList());
        List<BundleProduct> bundles = catalog.findApplicableBundles(wantedTierIds);

        var optional = request.optional();
        String networkType = optional == null ? null : mapNetwork(optional.networkType());
        ContractType contractType = parseContract(optional);
        Boolean hasFamilyBundle = optional == null ? null : optional.hasFamilyBundle();

        long dataMb = (long) required.monthlyDataGb() * MB_PER_GB;
        List<CandidatePlan> candidates = catalog.findCandidatePlans(dataMb, networkType);
        if (candidates.isEmpty()) {
            throw ApiException.noCandidate("조건을 만족하는 요금제가 없습니다.");
        }

        // 요금제별 planContractDiscount 는 CostCalculator 가 plan 에서 가져가므로 여기선 0 (placeholder).
        var ctx = new PricingContext(contractType, hasFamilyBundle, 0, bundles);
        List<CostResult> results = candidates.stream()
                .map(c -> toResult(c, calculator.calculate(c.plan(), wanted, ctx)))
                .sorted(Comparator.comparingLong(CostResult::monthlyTotal))
                .limit(TOP_N)
                .toList();

        List<MissingInput> missing = missingInputs(optional);
        Accuracy accuracy = missing.isEmpty() ? Accuracy.FULL : Accuracy.PARTIAL;
        return new RecommendationResponse(accuracy, missing, results);
    }

    /** 특정 조합(요금제 + 티어들)의 총비용. 후보 탐색·정렬 없이 1회 계산한다. */
    public CalculatorResponse calculate(CalculatorRequest request) {
        if (request.planId() == null) {
            throw ApiException.requiredMissing("planId", "요금제 ID가 필요합니다.");
        }
        if (request.tierIds() == null || request.tierIds().isEmpty()) {
            throw ApiException.requiredMissing("tierIds", "티어를 하나 이상 지정하세요.");
        }
        var candidate = catalog.findPlanById(request.planId())
                .orElseThrow(() -> ApiException.planNotFound("요금제를 찾을 수 없습니다: " + request.planId()));

        List<SubscriptionTier> tiers = catalog.findTiersByIds(request.tierIds());
        if (tiers.size() != request.tierIds().stream().distinct().count()) {
            throw ApiException.requiredMissing("tierIds", "존재하지 않는 티어 ID가 포함됐습니다.");
        }
        Set<SubscriptionTier> wanted = new LinkedHashSet<>(tiers);
        Set<Long> wantedTierIds = new LinkedHashSet<>(tiers.stream().map(SubscriptionTier::id).toList());
        List<BundleProduct> bundles = catalog.findApplicableBundles(wantedTierIds);

        var optional = request.optional();
        var ctx = new PricingContext(parseContract(optional),
                optional == null ? null : optional.hasFamilyBundle(), 0, bundles);
        CostResult result = toResult(candidate, calculator.calculate(candidate.plan(), wanted, ctx));

        List<MissingInput> missing = missingInputs(optional);
        Accuracy accuracy = missing.isEmpty() ? Accuracy.FULL : Accuracy.PARTIAL;
        return new CalculatorResponse(accuracy, missing, result);
    }

    private static CostResult toResult(CandidatePlan candidate, CostBreakdown breakdown) {
        var lines = breakdown.lines().stream().map(RecommendationService::toLine).toList();
        return new CostResult(candidate.plan().id(), candidate.plan().name(), candidate.carrier(),
                breakdown.effectiveMonthlyCost(), breakdown.baseline(),
                breakdown.monthlySavings(), breakdown.annualSavings(), lines);
    }

    private static BreakdownLine toLine(CostLine line) {
        return new BreakdownLine(line.label(), line.amount(),
                line.value().provenance().name(), line.note());
    }

    /** 채워지지 않은 선택 입력을 안내한다. accuracy 는 이 목록이 비었는지로 정한다. */
    private static List<MissingInput> missingInputs(RecommendationRequest.Optional o) {
        var missing = new ArrayList<MissingInput>();
        if (o == null || o.contractType() == null) {
            missing.add(new MissingInput("contractType",
                    "선택약정 25% 적용 시 통신비가 약 25% 절감될 수 있어요",
                    "통신사 고객센터 또는 마이페이지 > 약정 정보"));
        }
        if (o == null || o.hasFamilyBundle() == null) {
            missing.add(new MissingInput("hasFamilyBundle",
                    "가족 결합 시 결합할인이 추가로 반영돼요",
                    "통신사 마이페이지 > 결합 상품"));
        }
        if (o == null || o.currentCarrier() == null) {
            missing.add(new MissingInput("currentCarrier",
                    "현재 통신사를 알면 번호이동 여부를 판단할 수 있어요",
                    "현재 사용 중인 통신사 선택"));
        }
        if (o == null || o.networkType() == null) {
            missing.add(new MissingInput("networkType",
                    "망 종류를 지정하면 후보를 더 정확히 좁혀요",
                    "5G / LTE / 3G 중 선택"));
        }
        return missing;
    }

    private static ContractType parseContract(RecommendationRequest.Optional o) {
        if (o == null || o.contractType() == null) {
            return null;
        }
        try {
            return ContractType.valueOf(o.contractType());
        } catch (IllegalArgumentException e) {
            throw ApiException.requiredMissing("contractType", "약정 유형 값이 올바르지 않습니다.");
        }
    }

    private static String mapNetwork(String networkType) {
        if (networkType == null) {
            return null;
        }
        return switch (networkType) {
            case "5G", "FIVE_G" -> "FIVE_G";
            case "LTE", "4G" -> "LTE";
            case "3G", "THREE_G" -> "THREE_G";
            default -> throw ApiException.requiredMissing("networkType", "망 종류 값이 올바르지 않습니다.");
        };
    }

    private static long distinct(List<Long> ids) {
        return ids.stream().distinct().count();
    }
}
