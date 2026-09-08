package com.palsaekjo.yogobi.detection;

import com.palsaekjo.yogobi.catalog.CatalogReader;
import com.palsaekjo.yogobi.detection.domain.ActiveSubscription;
import com.palsaekjo.yogobi.detection.domain.DetectionFinding;
import com.palsaekjo.yogobi.pricing.domain.BundleProduct;
import com.palsaekjo.yogobi.pricing.domain.PlanBenefit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자의 활성 구독을 현재 요금제 혜택·번들과 대조해 중복 결제를 찾는다.
 * 계산 자체는 순수 도메인 {@link DuplicateDetector}에 위임하고, 여기선 DB 입출력만 한다.
 */
@Service
public class DetectionService {
    private final JdbcTemplate jdbc;
    private final CatalogReader catalog;
    private final DuplicateDetector detector = new DuplicateDetector();

    public DetectionService(JdbcTemplate jdbc, CatalogReader catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    /** 사용자의 탐지 결과를 새로 계산해 저장하고 반환한다(재실행 시 그 사용자 결과를 교체). */
    @Transactional
    public List<DetectionFinding> detectForUser(long userId) {
        List<ActiveSubscription> active = jdbc.query("""
                SELECT st.service_id, us.tier_id, st.name, us.monthly_price
                FROM user_subscription us JOIN subscription_tier st ON st.id = us.tier_id
                WHERE us.user_id = ? AND us.ended_at IS NULL
                """, (rs, i) -> new ActiveSubscription(rs.getLong("service_id"), rs.getLong("tier_id"),
                rs.getString("name"), rs.getLong("monthly_price")), userId);

        List<PlanBenefit> benefits = currentPlanBenefits(userId);
        Set<Long> tierIds = new LinkedHashSet<>(active.stream().map(ActiveSubscription::tierId).toList());
        List<BundleProduct> bundles = catalog.findApplicableBundles(tierIds);

        List<DetectionFinding> findings = detector.detect(active, benefits, bundles);

        jdbc.update("DELETE FROM detection_result WHERE user_id = ?", userId);
        for (DetectionFinding f : findings) {
            jdbc.update("""
                    INSERT INTO detection_result (user_id, rule_code, target_ref, wasted_amount)
                    VALUES (?, ?, ?, ?)
                    """, userId, f.rule().name(), f.targetRef(), f.wastedAmount());
        }
        return findings;
    }

    /** 현재 요금제가 설정돼 있으면 그 제휴 혜택을, 없으면 빈 목록(BENEFIT_OVERLAP 대상 없음). */
    private List<PlanBenefit> currentPlanBenefits(long userId) {
        Long planId = jdbc.queryForObject(
                "SELECT current_plan_id FROM app_user WHERE id = ?", Long.class, userId);
        if (planId == null) {
            return List.of();
        }
        return catalog.findPlanById(planId).map(p -> p.plan().benefits()).orElse(List.of());
    }
}
