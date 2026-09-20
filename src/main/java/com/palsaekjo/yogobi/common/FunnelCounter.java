package com.palsaekjo.yogobi.common;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-36 로그인 게이트 퍼널을 센다. "이탈율이 높으면 비회원에게 연다"는 기준은 숫자가 있어야 작동한다.
 *
 * <p>두 표에 쓴다. {@code funnel_daily} 는 <b>횟수</b>(원시값), {@code funnel_event} 는 <b>사람 수</b>다 —
 * 같은 행위자(회원 id 또는 발신지)는 하루에 단계당 한 번만 센다. 결과 화면이 추천을 무한 반복 호출하던
 * 9/17~18 에 횟수는 156회/10초씩 부풀었지만, 사람 수였다면 1 이었다(D-52).
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
    /** 회원이 캘린더에서 변경 시점 판정을 받았다. */
    public static final String CALENDAR_SHOWN = "CALENDAR_SHOWN";
    /** 회원이 결과를 마이페이지에 저장했다. */
    public static final String RESULT_SAVED = "RESULT_SAVED";

    /**
     * 화면이 알려 주는 단계다. <b>서버가 볼 수 없는 것만 여기 있다</b> — 입력은 전부 브라우저 안에서
     * 일어나 요청이 오지 않는다. 보고서 §9.2 "결과 도달률"의 분모가 {@code INPUT_STARTED} 다.
     *
     * <p>위의 다섯은 <b>절대 넣지 않는다.</b> 서버가 직접 관측하는 값이라, 화면이 보낼 수 있게 하면
     * 누구나 요청 한 번으로 리포트 조회 수를 부풀릴 수 있다. 공개 경로가 받는 것은 이 집합뿐이다.
     */
    public static final java.util.Set<String> CLIENT_REPORTED =
            java.util.Set.of("INPUT_STARTED", "INPUT_COMPLETED");

    private final JdbcTemplate jdbc;

    public FunnelCounter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 행위자 키. 회원은 id, 비회원은 발신지. 사람을 식별하려는 게 아니라 같은 사람을 두 번 안 세려는 것이다. */
    public static String actor(Principal principal, HttpServletRequest request) {
        return principal != null ? "u:" + principal.getName() : "ip:" + ClientAddress.of(request);
    }

    public static String actor(long userId) {
        return "u:" + userId;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String kind, String actorKey) {
        try {
            jdbc.update("""
                    INSERT INTO funnel_daily (day, kind, count) VALUES (CURRENT_DATE, ?, 1)
                    ON CONFLICT (day, kind) DO UPDATE SET count = funnel_daily.count + 1
                    """, kind);
            jdbc.update("""
                    INSERT INTO funnel_event (day, kind, actor_key) VALUES (CURRENT_DATE, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, kind, actorKey == null ? "?" : actorKey);
        } catch (RuntimeException e) {
            log.warn("퍼널 집계 실패 — 기능은 계속한다 ({}): {}", kind, e.getClass().getSimpleName());
        }
    }
}
