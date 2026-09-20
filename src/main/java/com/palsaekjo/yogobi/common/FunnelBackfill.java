package com.palsaekjo.yogobi.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 놓친 퍼널 단계를 <b>남아 있는 기록에서 되살린다</b>(2026-09-21, V31 후속).
 *
 * <p>{@code funnel_daily.kind} 의 CHECK 가 D-36 당시의 세 종류만 허용해서, D-52 에서 부르기 시작한
 * {@code CALENDAR_SHOWN}·{@code RESULT_SAVED} 가 2026-09-18 부터 한 건도 안 쌓였다. 집계 실패는
 * 삼켜지므로 WARN 한 줄만 남았고 화면에서는 "아무도 안 했다"와 구분되지 않았다.
 *
 * <p><b>되살릴 수 있는 것은 {@code RESULT_SAVED} 하나다.</b> 저장은 {@code saved_result} 에 행이
 * 남는다 — 누가·언제가 그대로 있으므로 추정이 아니라 복원이다. {@code CALENDAR_SHOWN} 은
 * 변경 시점 판정이 읽기 응답일 뿐 아무 행도 남기지 않아 <b>근거가 없다. 만들지 않는다</b> —
 * 다른 값으로 대신 세우면 그 순간 퍼널이 증거이기를 그만둔다. 화면 입력 단계 둘도 마찬가지로
 * 그 시절에는 존재하지 않던 신호다.
 *
 * <p>두 표의 경계가 다르다. {@code funnel_event} 는 (날짜·종류·행위자)가 기본키라 <b>오늘까지</b>
 * 넣어도 중복이 생기지 않는다. {@code funnel_daily} 는 횟수를 누적하므로 <b>어제까지만</b> 넣는다 —
 * 오늘은 배포된 순간부터 실시간 집계가 주인이고, 거기에 더하면 두 번 세게 된다.
 *
 * <p>매 기동마다 돈다. 둘 다 {@code ON CONFLICT DO NOTHING} 이라 여러 번 돌아도 결과가 같고,
 * 이미 쌓인 값은 절대 덮지 않는다.
 */
@Component
public class FunnelBackfill {
    private static final Logger log = LoggerFactory.getLogger(FunnelBackfill.class);

    private final JdbcTemplate jdbc;

    public FunnelBackfill(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        try {
            int people = jdbc.update("""
                    INSERT INTO funnel_event (day, kind, actor_key)
                    SELECT DISTINCT date(saved_at), 'RESULT_SAVED', 'u:' || user_id FROM saved_result
                    ON CONFLICT DO NOTHING
                    """);
            int days = jdbc.update("""
                    INSERT INTO funnel_daily (day, kind, count)
                    SELECT date(saved_at), 'RESULT_SAVED', count(*) FROM saved_result
                     WHERE date(saved_at) < CURRENT_DATE
                     GROUP BY date(saved_at)
                    ON CONFLICT DO NOTHING
                    """);
            if (people > 0 || days > 0)
                log.info("퍼널 복원: RESULT_SAVED 사람-일 {}건, 일별 횟수 {}일치를 saved_result 에서 되살렸다", people, days);
        } catch (RuntimeException e) {
            // 복원이 기동을 막지 않는다. 지난 기록을 못 되살리는 것과 서비스가 안 뜨는 것은 무게가 다르다.
            log.warn("퍼널 복원 실패 — 기동은 계속한다: {}", e.getMessage());
        }
    }
}
