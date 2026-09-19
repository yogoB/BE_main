package com.palsaekjo.yogobi.recommend;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * D-59 로 표본 기준을 바꾸면서 {@code member_savings} 가 빈 표로 시작했다. 그 전에 결과 화면을 본 회원의
 * 절감액이 랜딩에서 사라졌고, 없던 일이 되면 안 된다 — <b>저장해 둔 계산 요청을 지금 카탈로그로 다시 계산해</b>
 * 표본을 되살린다.
 *
 * <p>스냅숏 금액을 그대로 옮기지 않는 이유: {@code monthly_savings_vs_current} 는 V27(2026-09-18) 부터 채워져
 * 그 전 저장 건은 비어 있고, 그 뒤 건도 <b>그때의 카탈로그</b> 기준이다. 다시 계산하면 둘 다 지금 값으로 맞는다.
 * 다시 계산할 수 없는 건(요금제가 카탈로그에서 내려감)만 스냅숏 값으로 메운다.
 *
 * <p>계정당 한 행이고 <b>이미 행이 있으면 건드리지 않는다</b>({@code ON CONFLICT DO NOTHING}) — 살아 있는
 * 조회가 항상 이긴다. 그래서 매 기동마다 돌아도 결과가 같다. 실패해도 기동을 막지 않는다(fail-soft).
 */
@Component
public class MemberSavingsBackfill {
    private static final Logger log = LoggerFactory.getLogger(MemberSavingsBackfill.class);

    private final JdbcTemplate jdbc;
    private final RecommendationService service;
    private final ObjectMapper json;

    public MemberSavingsBackfill(JdbcTemplate jdbc, RecommendationService service, ObjectMapper json) {
        this.jdbc = jdbc;
        this.service = service;
        this.json = json;
    }

    /** 저장 1건: 그때의 계산 요청과, 다시 계산하지 못할 때 쓸 스냅숏 절감액. */
    private record Past(long userId, String request, Long snapshot, java.sql.Timestamp savedAt) { }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            int filled = backfill();
            if (filled > 0) log.info("절감액 표본 백필: {}명 되살림", filled);
        } catch (RuntimeException e) {
            log.warn("절감액 표본 백필 실패 — 표본은 지금 것부터 쌓인다 ({}: {})",
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** 되살린 계정 수. 여러 번 불러도 같다 — 이미 표본이 있는 계정은 건너뛴다. */
    public int backfill() {
        List<Past> past = jdbc.query("""
                SELECT DISTINCT ON (s.user_id) s.user_id, s.request::text, s.monthly_savings_vs_current, s.saved_at
                FROM saved_result s
                WHERE NOT EXISTS (SELECT 1 FROM member_savings m WHERE m.user_id = s.user_id)
                ORDER BY s.user_id, s.saved_at DESC
                """, (rs, i) -> new Past(rs.getLong(1), rs.getString(2), (Long) rs.getObject(3), rs.getTimestamp(4)));

        int filled = 0;
        for (Past row : past) {
            Long savings = recompute(row.request());
            if (savings == null) savings = row.snapshot();
            if (savings == null) continue;   // 지금 요금제를 안 알려준 저장 — 0 으로 적지 않는다(모름 ≠ 0)
            filled += jdbc.update("""
                    INSERT INTO member_savings(user_id, monthly_savings, seen_at) VALUES (?, ?, ?)
                    ON CONFLICT (user_id) DO NOTHING
                    """, row.userId(), savings, row.savedAt());
        }
        return filled;
    }

    /** 저장해 둔 요청을 지금 카탈로그로 다시 계산한다. 못 하면 null — 부르는 쪽이 스냅숏으로 메운다. */
    private Long recompute(String request) {
        try {
            CalculatorRequest parsed = json.readValue(request, CalculatorRequest.class);
            CostResult cost = service.calculate(parsed).result();
            return service.savingsVsCurrent(parsed, cost);
        } catch (Exception e) {
            return null;
        }
    }
}
