package com.palsaekjo.yogobi.recommend;

import com.palsaekjo.yogobi.catalog.CatalogCandidateRecorder;
import com.palsaekjo.yogobi.catalog.CatalogCandidateRecorder.Kind;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
    /**
     * 통신사 이름이 아니라 분류인 값들. 예전 프론트가 알뜰폰 브랜드를 '알뜰폰' 하나로 묶어 보냈는데,
     * 그걸 결손으로 세면 "알뜰폰을 수집하라" 는 쓸모없는 행만 쌓인다. 배포 시차 동안 옛 화면이
     * 보내는 값도 있으므로 남겨 둔다.
     */
    private static final Set<String> GENERIC_CARRIERS = Set.of("알뜰폰", "MVNO", "기타");

    private final CatalogCandidateRecorder gaps;
    private final PriceCrossCheck crossCheck;
    private final CostCalculator calculator = new CostCalculator();

    public RecommendationService(CatalogReader catalog, CatalogCandidateRecorder gaps, PriceCrossCheck crossCheck) {
        this.catalog = catalog;
        this.gaps = gaps;
        this.crossCheck = crossCheck;
    }

    /**
     * 서비스마다 쓸 등급을 고른다. 사용자가 지정한 등급이 있으면 그것을, 없는 서비스는 대표 등급으로 채운다.
     *
     * <p>지정 전에는 언제나 대표 등급(스탠다드 우선)으로 계산했다. 프리미엄을 쓰는 사람에게 스탠다드
     * 금액을 보여주고 있었다는 뜻이다 — 화면에서 등급을 고를 수 있게 되면서 그 값이 여기까지 온다.
     *
     * <p>요청한 서비스에 속하지 않는 등급 id 는 버린다. 고르지 않은 서비스의 등급으로 금액을 만들지 않는다.
     */
    private List<SubscriptionTier> chooseTiers(RecommendationRequest.Required required) {
        List<SubscriptionTier> representative = catalog.findRepresentativeTiers(required.wantedServiceIds());
        List<Long> picked = required.wantedTierIds();
        if (picked == null || picked.isEmpty()) {
            return representative;
        }
        Set<Long> services = new LinkedHashSet<>(required.wantedServiceIds());
        Map<Long, SubscriptionTier> byService = new LinkedHashMap<>();
        for (SubscriptionTier tier : representative) {
            byService.put(tier.serviceId(), tier);
        }
        for (SubscriptionTier tier : catalog.findTiersByIds(picked)) {
            if (services.contains(tier.serviceId())) {
                byService.put(tier.serviceId(), tier);
            }
        }
        return List.copyOf(byService.values());
    }

    public RecommendationResponse recommend(RecommendationRequest request) {
        var required = request.required();
        if (required == null || required.monthlyDataGb() == null || required.monthlyDataGb() <= 0) {
            throw ApiException.requiredMissing("monthlyDataGb", "월 데이터 사용량(GB)이 필요합니다.");
        }
        if (required.wantedServiceIds() == null || required.wantedServiceIds().isEmpty()) {
            throw ApiException.requiredMissing("wantedServiceIds", "원하는 서비스를 하나 이상 선택하세요.");
        }

        // 카탈로그에 없는 서비스는 막지 않는다(G-12·D-17). 아는 것으로 계산하고 모르는 것은 안내·기록한다.
        List<SubscriptionTier> tiers = chooseTiers(required);
        List<Long> excluded = unknown(required.wantedServiceIds(), tiers);
        // 그중 해외 결제 구독은 **결손이 아니다** — 수집할 게 아니라 사용자에게 실제 결제액을 물어야 하는 것이다.
        Map<Long, String> foreignPriced = catalog.findForeignPricedServices(excluded);
        List<Long> unknownServiceIds = excluded.stream().filter(id -> !foreignPriced.containsKey(id)).toList();
        unknownServiceIds.forEach(id -> gaps.record(Kind.SUBSCRIPTION_TIER, "serviceId:" + id));
        Set<SubscriptionTier> wanted = new LinkedHashSet<>(tiers);
        Set<Long> wantedTierIds = new LinkedHashSet<>(tiers.stream().map(SubscriptionTier::id).toList());
        List<BundleProduct> bundles = catalog.findApplicableBundles(wantedTierIds);

        var optional = request.optional();
        String networkType = optional == null ? null : mapNetwork(optional.networkType());
        ContractType contractType = parseContract(optional);
        Boolean hasFamilyBundle = optional == null ? null : optional.hasFamilyBundle();
        Long familyDiscount = familyBundleDiscount(optional);
        recordUnknownCarrier(optional);
        java.util.Optional<CandidatePlan> currentPlan = findCurrentPlan(optional);
        String currentCarrier = currentCarrier(optional, currentPlan);

        long dataMb = (long) required.monthlyDataGb() * MB_PER_GB;
        List<CandidatePlan> candidates = catalog.findCandidatePlans(dataMb, networkType);
        if (candidates.isEmpty()) {
            gaps.record(Kind.MOBILE_PLAN, "dataMb>=" + dataMb + ",network=" + (networkType == null ? "ANY" : networkType));
            var noPlan = missingInputs(optional, currentCarrier, familyDiscount, unknownServiceIds, foreignPriced);
            addAgeRestrictionNotice(noPlan, dataMb, networkType);
            noPlan.add(new MissingInput("monthlyDataGb",
                    "조건을 만족하는 요금제가 아직 카탈로그에 없어요. 확인 중이에요",
                    "데이터 사용량을 낮추거나 망 종류를 바꿔서 다시 찾아보세요"));
            return new RecommendationResponse(Accuracy.PARTIAL, noPlan, List.of());
        }

        // 요금제별 planContractDiscount 는 CostCalculator 가 plan 에서 가져가므로 여기선 0 (placeholder).
        var ctx = new PricingContext(contractType, hasFamilyBundle, familyDiscount, 0, bundles);
        // 통신사를 옮기면 결합이 풀린다. 그 후보에는 할인액을 빼고 계산한다(G-29) — 결합 여부 자체는
        // 그대로 둔다. 금액에서 빠지는 것은 할인액뿐이고, hasFamilyBundle 은 accuracy 판정에 쓰인다.
        var ctxWithoutBundle = new PricingContext(contractType, hasFamilyBundle, null, 0, bundles);
        // 같은 계산 결과로 정렬하고 상위 N개만 응답으로 변환한다.
        List<CostResult> results = candidates.stream()
                .map(c -> Map.entry(c, calculator.calculate(c.plan(), wanted,
                        sameCarrier(c.carrier(), currentCarrier) ? ctx : ctxWithoutBundle)))
                .sorted(Comparator.comparingLong(e -> e.getValue().effectiveMonthlyCost()))
                .limit(TOP_N)
                // 대조는 정렬이 끝난 뒤에 붙인다 — 검증값이 순위·금액에 끼어들 여지를 없앤다(D-03·D-20).
                .map(e -> toResult(e.getKey(), e.getValue()).withPriceCrossCheck(crossCheck.check(e.getKey())))
                .toList();

        // 현재 요금제는 지금 통신사의 요금제다 — 결합 할인이 붙어 있는 쪽이라 ctx 를 그대로 쓴다(G-30 f).
        var current = currentPlan
                .map(p -> currentCost(toResult(p, calculator.calculate(p.plan(), wanted, ctx)), results))
                .orElse(null);

        List<MissingInput> missing = missingInputs(optional, currentCarrier, familyDiscount,
                unknownServiceIds, foreignPriced);
        addAgeRestrictionNotice(missing, dataMb, networkType);
        addFamilyBundleCarrierNotice(missing, familyDiscount, currentCarrier, results);
        addUnknownCurrentPlanNotice(missing, optional, currentPlan);
        Accuracy accuracy = missing.isEmpty() ? Accuracy.FULL : Accuracy.PARTIAL;
        return new RecommendationResponse(accuracy, missing, results, candidates.size(), current);
    }

    /** 현재 요금제를 1순위와 나란히 놓는다. 절감액은 여기서 만든다 — 화면이 두 금액을 빼지 않도록(원칙 2). */
    private static RecommendationResponse.CurrentCost currentCost(CostResult cost, List<CostResult> results) {
        long monthly = cost.monthlyTotal() - results.get(0).monthlyTotal();
        return new RecommendationResponse.CurrentCost(cost, monthly, monthly * 12);
    }

    /** {@code currentPlanId} 가 있으면 카탈로그에서 찾는다. 없는 id 는 막지 않고 빈 값으로 둔다(G-30 d). */
    private java.util.Optional<CandidatePlan> findCurrentPlan(RecommendationRequest.Optional o) {
        if (o == null || o.currentPlanId() == null) {
            return java.util.Optional.empty();
        }
        return catalog.findPlanById(o.currentPlanId());
    }

    /**
     * 현재 통신사. 요금제를 골랐다면 그 통신사가 확실하므로 이름보다 앞선다 — 이름은 오타가 나지만
     * 요금제 id 는 우리 카탈로그에서 고른 값이다(G-29 f).
     *
     * <p>"알뜰폰" 같은 분류는 <b>모르는 것으로 친다.</b> 어느 통신사인지 지목하지 못하는 값이라
     * 결합 할인을 어디에 붙일지 정할 수 없고, 그걸 통신사처럼 다루면 "알뜰폰 요금제에만 반영했어요"
     * 같은 말이 안 되는 안내가 나간다.
     */
    private static String currentCarrier(RecommendationRequest.Optional o, java.util.Optional<CandidatePlan> plan) {
        if (plan.isPresent()) {
            return plan.get().carrier();
        }
        String name = o == null ? null : o.currentCarrier();
        return name == null || name.isBlank() || GENERIC_CARRIERS.contains(name.strip()) ? null : name;
    }

    /**
     * 같은 통신사인가. `carrierExists` 와 같은 규칙으로 본다 — 공백·대소문자 무시.
     * 어느 한쪽을 모르면 <b>같다고 하지 않는다.</b> 모르는 것을 같다고 하면 없어질 할인을 빼게 된다.
     */
    private static boolean sameCarrier(String a, String b) {
        return a != null && b != null
                && a.replace(" ", "").equalsIgnoreCase(b.replace(" ", ""));
    }

    /** 특정 조합(요금제 + 티어들)의 총비용. 후보 탐색·정렬 없이 1회 계산한다. */
    public CalculatorResponse calculate(CalculatorRequest request) {
        if (request.planId() == null) {
            throw ApiException.requiredMissing("planId", "요금제 ID가 필요합니다.");
        }
        if (request.tierIds() == null || request.tierIds().isEmpty()) {
            throw ApiException.requiredMissing("tierIds", "구독 등급을 하나 이상 지정하세요.");
        }
        var candidate = catalog.findPlanById(request.planId())
                .orElseThrow(() -> ApiException.planNotFound("요금제를 찾을 수 없습니다: " + request.planId()));

        // 원화 확정 가격 등급만 계산에 들어간다. 빠진 ID 가 해외 결제 등급이면 잘못된 요청이 아니라
        // "실제 결제액을 물어야 하는 것"이므로 막지 않고 안내로 돌려준다(원칙 5-①).
        List<SubscriptionTier> tiers = catalog.findTiersByIds(request.tierIds());
        List<Long> absent = request.tierIds().stream().distinct()
                .filter(id -> tiers.stream().noneMatch(t -> t.id() == id)).toList();
        Map<Long, String> foreignPriced = catalog.findForeignPricedTiers(absent);
        if (absent.size() != foreignPriced.size()) {
            throw ApiException.requiredMissing("tierIds", "존재하지 않는 구독 등급 ID가 포함됐습니다.");
        }
        Set<SubscriptionTier> wanted = new LinkedHashSet<>(tiers);
        Set<Long> wantedTierIds = new LinkedHashSet<>(tiers.stream().map(SubscriptionTier::id).toList());
        List<BundleProduct> bundles = catalog.findApplicableBundles(wantedTierIds);

        var optional = request.optional();
        // 고른 요금제가 지금 통신사가 아니면 결합은 풀린다 — 추천 경로와 같은 규칙이다(G-29).
        String currentCarrier = currentCarrier(optional, findCurrentPlan(optional));
        Long familyDiscount = familyBundleDiscount(optional);
        var ctx = new PricingContext(parseContract(optional),
                optional == null ? null : optional.hasFamilyBundle(),
                sameCarrier(candidate.carrier(), currentCarrier) ? familyDiscount : null, 0, bundles);
        CostResult result = toResult(candidate, calculator.calculate(candidate.plan(), wanted, ctx))
                .withPriceCrossCheck(crossCheck.check(candidate));

        // 계산기는 사용자가 카탈로그에서 고른 ID를 받는다 — 없는 ID는 결손이 아니라 잘못된 요청이라 400 그대로다.
        // 다만 해외 결제 등급은 위에서 걸러 안내로 싣는다.
        List<MissingInput> missing = missingInputs(optional, currentCarrier, familyDiscount, List.of(), foreignPriced);
        Accuracy accuracy = missing.isEmpty() ? Accuracy.FULL : Accuracy.PARTIAL;
        return new CalculatorResponse(accuracy, missing, result);
    }

    private CostResult toResult(CandidatePlan candidate, CostBreakdown breakdown) {
        var lines = breakdown.lines().stream().map(RecommendationService::toLine).toList();
        return new CostResult(candidate.plan().id(), candidate.plan().name(), candidate.carrier(),
                breakdown.effectiveMonthlyCost(), breakdown.baseline(),
                breakdown.monthlySavings(), breakdown.annualSavings(), lines);
    }

    private static BreakdownLine toLine(CostLine line) {
        return new BreakdownLine(line.label(), line.amount(),
                line.value().provenance().name(), line.note());
    }

    /**
     * 가입 자격이 없어 후보에서 뺀 요금제를 안내한다(G-18-g). 0 건이면 넣지 않는다 — 없는 선택지를 안내하면 소음이다(G-18-h).
     * <p>계산기 경로에는 붙이지 않는다. 사용자가 이미 고른 요금제 하나를 계산하는 것이라 후보 탐색이 없다.
     */
    private void addAgeRestrictionNotice(List<MissingInput> missing, long dataMb, String networkType) {
        int restricted = catalog.countAgeRestricted(dataMb, networkType);
        if (restricted == 0) {
            return;
        }
        missing.add(new MissingInput("ageLimit",
                "청년·키즈·시니어처럼 가입 자격이 필요한 요금제 " + restricted + "건은 뺐어요",
                "해당 자격이 있다면 통신사에서 더 싼 요금제를 찾을 수 있어요"));
    }

    /**
     * 결합 할인을 <b>어디에 반영했는지</b> 밝힌다(G-29 c·e). 금액이 없으면 할 말도 없다.
     *
     * <p>이 안내가 없으면 사용자는 결과 표의 "가족결합 할인 −11,000원"이 모든 후보에 붙어 있다고
     * 읽는다. 실제로는 지금 통신사 요금제에만 붙어 있고, 1등이 다른 통신사면 그 금액은 사라진다.
     */
    private static void addFamilyBundleCarrierNotice(List<MissingInput> missing, Long familyDiscount,
            String currentCarrier, List<CostResult> results) {
        if (familyDiscount == null) {
            return;
        }
        String amount = String.format("%,d", familyDiscount);
        if (currentCarrier == null) {
            missing.add(new MissingInput("currentCarrier",
                    "가족결합 할인 " + amount + "원은 이번 계산에 넣지 않았어요. 어느 통신사에 붙은 할인인지 알아야 하거든요",
                    "지금 쓰는 통신사(또는 요금제)를 골라 주세요"));
            return;
        }
        if (results.stream().anyMatch(r -> !sameCarrier(r.carrier(), currentCarrier))) {
            missing.add(new MissingInput("familyBundleDiscountKrw",
                    "가족결합 할인 " + amount + "원은 " + currentCarrier + " 요금제에만 반영했어요. 다른 통신사로 옮기면 결합이 풀리거든요",
                    "지금 통신사 안에서 바꾸면 할인은 그대로예요"));
        }
    }

    /** 보낸 요금제를 못 찾았을 때. 400 으로 막지 않는다 — '현재' 금액 하나 때문에 추천 전체를 버릴 이유가 없다. */
    private static void addUnknownCurrentPlanNotice(List<MissingInput> missing, RecommendationRequest.Optional o,
            java.util.Optional<CandidatePlan> currentPlan) {
        if (o == null || o.currentPlanId() == null || currentPlan.isPresent()) {
            return;
        }
        missing.add(new MissingInput("currentPlanId",
                "지금 쓰는 요금제를 카탈로그에서 찾지 못해 '현재' 금액은 계산하지 못했어요",
                "요금제를 다시 골라 주세요. 목록에 없다면 이름을 알려주시면 확인할게요"));
    }

    /** 채워지지 않은 선택 입력과 카탈로그 결손을 안내한다. accuracy 는 이 목록이 비었는지로 정한다. */
    private static List<MissingInput> missingInputs(RecommendationRequest.Optional o, String currentCarrier,
            Long familyDiscount, List<Long> unknownServiceIds, Map<Long, String> foreignPriced) {
        var missing = new ArrayList<MissingInput>();
        if (!foreignPriced.isEmpty()) {
            // 해외 결제 구독은 원화 확정 금액이 없다. 환율 환산값은 표시용이라 계산에 넣지 않는다(D-17).
            missing.add(new MissingInput("wantedServiceIds",
                    String.join("·", foreignPriced.values()) + "는 해외 결제라 원화 금액이 확정되지 않아 계산에서 뺐어요",
                    "마이페이지 > 내 구독에 실제 결제액을 넣으면 그 금액으로 반영돼요"));
        }
        if (!unknownServiceIds.isEmpty()) {
            missing.add(new MissingInput("wantedServiceIds",
                    "아직 카탈로그에 없는 서비스(ID " + join(unknownServiceIds) + ")는 계산에서 뺐어요",
                    "확인 중이에요. 금액을 알고 있다면 계산기에서 직접 입력해 바로 반영할 수 있어요"));
        }
        if (o == null || o.contractType() == null) {
            missing.add(new MissingInput("contractType",
                    "선택약정 25% 적용 시 통신비가 약 25% 절감될 수 있어요",
                    "통신사 고객센터 또는 마이페이지 > 약정 정보"));
        }
        if (o == null || o.hasFamilyBundle() == null) {
            // 결합 중이라고 하면 할인액을 물어 그 금액을 뺀다(G-28). 그러니 여기서 약속해도 된다 —
            // 단, 깎이는 것은 사용자가 적어 준 금액이지 우리가 계산한 값이 아니다.
            missing.add(new MissingInput("hasFamilyBundle",
                    "가족 결합 중이라면 할인액을 알려주세요. 그 금액을 빼고 계산해요",
                    "통신사 마이페이지 > 결합 상품"));
        }
        // 결합 중이라고 했는데 할인액을 모르면 그만큼 금액이 덜 깎인다. 우리가 만들 수 없는 값이라 묻는다(G-28 b).
        if (o != null && Boolean.TRUE.equals(o.hasFamilyBundle()) && o.familyBundleDiscountKrw() == null) {
            missing.add(new MissingInput("familyBundleDiscountKrw",
                    "가족결합으로 매달 얼마를 할인받는지 알려주시면 그 금액을 빼고 계산해요",
                    "통신사 앱 > 요금 청구서의 결합할인 항목"));
        }
        // 결합 할인이 있는데 통신사를 모르면 addFamilyBundleCarrierNotice 가 더 구체적으로 묻는다.
        // 같은 것을 두 줄로 묻지 않는다(G-18-h 안내 소음).
        if (currentCarrier == null && familyDiscount == null) {
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

    /**
     * 사용자가 적어 준 결합 할인액. 음수는 받지 않는다 — 요금을 올리는 "할인"은 입력 실수다.
     * 결합 중이 아니라고 했으면 금액이 와도 무시한다(G-28 c).
     */
    private static Long familyBundleDiscount(RecommendationRequest.Optional optional) {
        if (optional == null || optional.familyBundleDiscountKrw() == null) {
            return null;
        }
        if (optional.familyBundleDiscountKrw() < 0) {
            throw ApiException.requiredMissing("familyBundleDiscountKrw", "가족결합 할인액은 0원 이상으로 입력해 주세요.");
        }
        return Boolean.TRUE.equals(optional.hasFamilyBundle()) ? optional.familyBundleDiscountKrw() : null;
    }

    /**
     * 카탈로그에 없는 통신사를 <b>결손으로 남긴다</b>. 사용자가 직접 적은 통신사가 우리에게 없다는 것은
     * "아예 없다" 이고, 그게 `catalog_candidate` 가 세는 값이다(D-17 결손 기록, 계산에는 쓰지 않는다).
     *
     * <p>지금까지 `currentCarrier` 는 받아만 두고 아무 데도 쓰지 않았다. 수집 우선순위를 정하는 신호로 쓴다 —
     * 많이 적힐수록 `requested_cnt` 가 올라가고 백오피스 결손 목록 위로 온다.
     *
     * <p>기록이 실패해도 추천은 그대로 나간다(`CatalogCandidateRecorder` 가 fail-soft 다).
     */
    private void recordUnknownCarrier(RecommendationRequest.Optional optional) {
        String carrier = optional == null ? null : optional.currentCarrier();
        if (carrier == null || carrier.isBlank() || GENERIC_CARRIERS.contains(carrier.strip())) {
            return;
        }
        if (!catalog.carrierExists(carrier)) {
            gaps.record(Kind.MOBILE_PLAN, "carrier:" + carrier.strip());
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

    /** 요청한 서비스 중 카탈로그에 없는(또는 비활성인) 것. 순서 유지·중복 제거. */
    private static List<Long> unknown(List<Long> requestedServiceIds, List<SubscriptionTier> found) {
        Set<Long> known = found.stream().map(SubscriptionTier::serviceId).collect(Collectors.toSet());
        return requestedServiceIds.stream().distinct().filter(id -> !known.contains(id)).toList();
    }

    private static String join(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }
}
