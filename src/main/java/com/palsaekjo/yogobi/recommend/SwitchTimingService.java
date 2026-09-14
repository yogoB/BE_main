package com.palsaekjo.yogobi.recommend;

import com.palsaekjo.yogobi.catalog.CatalogReader;
import com.palsaekjo.yogobi.common.ApiException;
import com.palsaekjo.yogobi.pricing.CostCalculator;
import com.palsaekjo.yogobi.pricing.SwitchTiming;
import com.palsaekjo.yogobi.pricing.domain.PricingContext;
import com.palsaekjo.yogobi.pricing.domain.SubscriptionTier;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 변경 시점(회수기간) — 회원의 현재 요금제+구독(A1 저장분)으로 계산한 현재 실질월비용을 대상 요금제와 비교한다(domain §8).
 * 금액 계산은 {@link CostCalculator}(현재/대상 동일 엔진), 판정은 순수 {@link SwitchTiming}. 전환비용·약정잔여는 사용자 추정치(입력).
 */
@Service
public class SwitchTimingService {
    private final CatalogReader catalog;
    private final JdbcTemplate jdbc;
    private final CostCalculator calculator = new CostCalculator();

    public SwitchTimingService(CatalogReader catalog, JdbcTemplate jdbc) {
        this.catalog = catalog;
        this.jdbc = jdbc;
    }

    public record Response(long currentMonthlyCost, long targetMonthlyCost, long monthlySavings,
                           long switchingCost, Integer paybackMonths, int remainingContractMonths, String status) {
    }

    public Response evaluate(long userId, long targetPlanId, long switchingCost, int remainingContractMonths) {
        Long currentPlanId = jdbc.queryForObject("SELECT current_plan_id FROM app_user WHERE id = ?", Long.class, userId);
        if (currentPlanId == null) {
            throw ApiException.requiredMissing("currentPlan", "현재 요금제를 먼저 설정하세요 (POST /me/current-plan).");
        }
        // 현재·대상 모두 사용자의 활성 구독(같은 집합)을 얹어 같은 조건으로 비교한다.
        List<Long> tierIds = jdbc.queryForList(
                "SELECT tier_id FROM user_subscription WHERE user_id = ? AND ended_at IS NULL", Long.class, userId);

        long current = effectiveCost(currentPlanId, tierIds);
        long target = effectiveCost(targetPlanId, tierIds);
        var result = SwitchTiming.evaluate(switchingCost, current - target, remainingContractMonths);
        return new Response(current, target, result.monthlySavings(), result.switchingCost(),
                result.paybackMonths(), result.remainingContractMonths(), result.status().name());
    }

    private long effectiveCost(long planId, List<Long> tierIds) {
        var candidate = catalog.findPlanById(planId)
                .orElseThrow(() -> ApiException.planNotFound("요금제를 찾을 수 없습니다: " + planId));
        List<SubscriptionTier> tiers = catalog.findTiersByIds(tierIds);
        var wanted = new LinkedHashSet<>(tiers);
        var bundles = catalog.findApplicableBundles(new LinkedHashSet<>(tierIds));
        var ctx = new PricingContext(null, null, 0, bundles);
        return calculator.calculate(candidate.plan(), wanted, ctx).effectiveMonthlyCost();
    }
}
