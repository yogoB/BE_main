package com.palsaekjo.yogobi.recommend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어떤 요금제가 1순위로 얼마나 나가는지, 데이터 구간 분포가 어떤지(D-52 ⑦). 날짜·요금제·GB 단위 횟수만 —
 * 개인은 남기지 않는다. 수집 우선순위와 제품 판단의 근거다. 실패해도 추천은 그대로 나간다.
 */
@Component
public class RecommendationStats {
    private static final Logger log = LoggerFactory.getLogger(RecommendationStats.class);
    private final JdbcTemplate jdbc;

    public RecommendationStats(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long topPlanId, int dataGb) {
        try {
            jdbc.update("""
                    INSERT INTO recommendation_daily (day, plan_id, data_gb, count) VALUES (CURRENT_DATE, ?, ?, 1)
                    ON CONFLICT (day, plan_id, data_gb) DO UPDATE SET count = recommendation_daily.count + 1
                    """, topPlanId, dataGb);
        } catch (RuntimeException e) {
            log.warn("추천 통계 기록 실패 — 기능은 계속한다: {}", e.getClass().getSimpleName());
        }
    }
}
