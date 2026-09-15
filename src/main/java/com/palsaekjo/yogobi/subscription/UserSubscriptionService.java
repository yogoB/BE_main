package com.palsaekjo.yogobi.subscription;

import com.palsaekjo.yogobi.common.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 본인의 구독·현재 요금제 관리. 모든 조회·변경은 요청자 userId로 제한한다(객체 단위 권한 — 남의 데이터 접근 불가).
 * 금액(monthly_price)은 사용자가 실제 내는 값(USER_PROVIDED). 탐지·현재 지출 계산의 입력원.
 */
@Service
public class UserSubscriptionService {
    private final JdbcTemplate jdbc;

    public UserSubscriptionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record View(long id, long tierId, String tierName, long monthlyPrice, LocalDate startedAt, LocalDate endedAt) {
    }

    public List<View> list(long userId) {
        return jdbc.query("""
                SELECT us.id, us.tier_id, st.name, us.monthly_price, us.started_at, us.ended_at
                FROM user_subscription us JOIN subscription_tier st ON st.id = us.tier_id
                WHERE us.user_id = ? ORDER BY us.id
                """, (rs, i) -> new View(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4),
                date(rs, 5), date(rs, 6)), userId);
    }

    @Transactional
    public View add(long userId, Long tierId, Long monthlyPrice) {
        if (tierId == null) {
            throw ApiException.requiredMissing("tierId", "구독 등급 ID가 필요합니다.");
        }
        if (monthlyPrice == null || monthlyPrice < 0) {
            throw ApiException.requiredMissing("monthlyPrice", "월 결제액은 0 이상 정수여야 합니다.");
        }
        if (jdbc.queryForObject("SELECT count(*) FROM subscription_tier WHERE id = ?", Integer.class, tierId) == 0) {
            throw ApiException.requiredMissing("tierId", "존재하지 않는 구독 등급입니다.");
        }
        long id = jdbc.queryForObject("""
                INSERT INTO user_subscription (user_id, tier_id, monthly_price, started_at)
                VALUES (?, ?, ?, CURRENT_DATE) RETURNING id
                """, Long.class, userId, tierId, monthlyPrice);
        return list(userId).stream().filter(v -> v.id() == id).findFirst().orElseThrow();
    }

    /** 본인 구독만 삭제한다. 없거나 남의 것이면 404. */
    public void remove(long userId, long id) {
        if (jdbc.update("DELETE FROM user_subscription WHERE id = ? AND user_id = ?", id, userId) != 1) {
            throw new ApiException("YGB-SUB-404", 404, "구독을 찾을 수 없습니다.", null);
        }
    }

    @Transactional
    public void setCurrentPlan(long userId, Long planId) {
        if (planId == null) {
            throw ApiException.requiredMissing("planId", "요금제 ID가 필요합니다.");
        }
        if (jdbc.queryForObject("SELECT count(*) FROM mobile_plan WHERE id = ?", Integer.class, planId) == 0) {
            throw ApiException.planNotFound("요금제를 찾을 수 없습니다: " + planId);
        }
        jdbc.update("UPDATE app_user SET current_plan_id = ? WHERE id = ?", planId, userId);
    }

    private static LocalDate date(ResultSet rs, int col) throws SQLException {
        var d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }
}
