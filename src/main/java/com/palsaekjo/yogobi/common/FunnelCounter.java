package com.palsaekjo.yogobi.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-36 로그인 게이트 퍼널을 센다. "이탈율이 높으면 비회원에게 연다"는 기준은 숫자가 있어야 작동한다.
 *
 * <p>세 값 모두 **실측**이다. 이탈은 빼서 구하지만(게이트 − 리포트) 그 두 값이 측정값이라
 * 지어낸 숫자가 섞이지 않는다.
 *
 * <p><b>집계 실패가 기능을 막지 않는다.</b> 추천 응답 경로에서 부르므로, 카운터가 죽어도
 * 사용자는 결과를 받아야 한다 — 실패는 로그만 남기고 삼킨다. 별도 트랜잭션이라 호출자의
 * 롤백에 휘말리지도 않는다.
 */
@Component
public class FunnelCounter {
    private static final Logger log = LoggerFactory.getLogger(FunnelCounter.class);

    /** 비회원이 추천 결과를 받았다 = 로그인 게이트를 만났다. */
    public static final String GATE_SHOWN = "GATE_SHOWN";
    /** 회원이 추천 결과를 받았다 = 리포트를 실제로 봤다. */
    public static final String REPORT_SHOWN = "REPORT_SHOWN";
    /** Google 로그인이 성공했다(가입 포함). */
    public static final String MEMBER_LOGIN = "MEMBER_LOGIN";

    private final JdbcTemplate jdbc;

    public FunnelCounter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String kind) {
        try {
            jdbc.update("""
                    INSERT INTO funnel_daily (day, kind, count) VALUES (CURRENT_DATE, ?, 1)
                    ON CONFLICT (day, kind) DO UPDATE SET count = funnel_daily.count + 1
                    """, kind);
        } catch (RuntimeException e) {
            log.warn("퍼널 집계 실패 — 기능은 계속한다 ({}): {}", kind, e.getClass().getSimpleName());
        }
    }
}
