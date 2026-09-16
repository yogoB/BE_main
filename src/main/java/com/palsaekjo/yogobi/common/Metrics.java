package com.palsaekjo.yogobi.common;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 운영 지표(D-25). 엔드포인트별 지연·요청수는 micrometer 가 자동으로 붙이므로(`http_server_requests`),
 * 여기에는 DB 를 읽어야 아는 값만 둔다. Fly 프로메테우스가 긁어 fly-metrics.net 그라파나에 그린다.
 *
 * Gauge 는 스크레이프 시점에 평가되므로 스케줄러가 필요 없다. 대신 매 스크레이프마다 쿼리가 나가므로
 * 집계 비용이 큰 값은 넣지 않는다 — 전부 인덱스 없는 count 지만 회원 규모에서는 무시할 만하다.
 */
@Configuration
public class Metrics {
    @Bean
    MeterBinder yogobiMetrics(JdbcTemplate db) {
        return registry -> {
            gauge(registry, db, "yogobi.members", "SELECT count(*) FROM app_user");
            gauge(registry, db, "yogobi.members.signed_up_24h",
                    "SELECT count(*) FROM app_user WHERE created_at > now() - interval '24 hours'");
            // 가입만 하고 떠났는지, 실제로 구독을 등록했는지 — 둘의 비율이 전환율이다.
            gauge(registry, db, "yogobi.members.with_subscription",
                    "SELECT count(DISTINCT user_id) FROM user_subscription WHERE ended_at IS NULL");
            gauge(registry, db, "yogobi.sessions.active",
                    "SELECT count(*) FROM auth_session WHERE expires_at > now()");
        };
    }

    private static void gauge(MeterRegistry registry, JdbcTemplate db, String name, String sql) {
        Gauge.builder(name, () -> count(db, sql)).register(registry);
    }

    /** DB 가 흔들릴 때 JVM·HTTP 지표까지 같이 잃지 않도록, 값 없음(NaN)으로 내보내고 스크레이프는 살린다. */
    private static double count(JdbcTemplate db, String sql) {
        try {
            Long value = db.queryForObject(sql, Long.class);
            return value == null ? Double.NaN : value;
        } catch (DataAccessException e) {
            return Double.NaN;
        }
    }
}
