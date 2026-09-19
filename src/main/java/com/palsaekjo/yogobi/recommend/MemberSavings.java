package com.palsaekjo.yogobi.recommend;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 절감액 표본 기록(D-59). 표본은 <b>로그인한 채 결과 화면을 본 회원</b>이다 — 저장 버튼을 누른 회원이 아니다.
 *
 * <p>계정당 한 행이고 최근 본 결과가 덮어쓴다. 여러 번 본다고 여러 번 세지 않는다.
 * 지금 요금제를 알려준 경우에만 기록한다 — {@code current} 가 null 이면 절감액을 만들 수 없고,
 * 0 으로 적으면 "절감이 없다"와 "모른다"가 섞인다(D-53).
 *
 * <p>음수(지금이 더 싸다)도 그대로 적는다. 거르는 것은 집계 쪽 일이다 — 여기서 버리면 다음번에
 * 더 나쁜 결과를 봐도 예전 좋은 표본이 남는다.
 */
@Component
public class MemberSavings {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MemberSavings.class);
    private final JdbcTemplate jdbc;

    public MemberSavings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 집계용 부수 기록이라 실패해도 결과 응답을 막지 않는다 — 대신 침묵하지 않는다. */
    public void record(long userId, long monthlySavings) {
        try {
            jdbc.update("""
                    INSERT INTO member_savings(user_id, monthly_savings) VALUES (?, ?)
                    ON CONFLICT (user_id) DO UPDATE SET monthly_savings = EXCLUDED.monthly_savings, seen_at = now()
                    """, userId, monthlySavings);
        } catch (DataAccessException e) {
            log.warn("절감액 표본 기록 실패 user={}: {}", userId, e.getMessage());
        }
    }
}
