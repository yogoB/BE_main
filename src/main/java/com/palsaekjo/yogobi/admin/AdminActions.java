package com.palsaekjo.yogobi.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 운영자가 한 일을 남긴다(D-52 감사 통합). 카탈로그 변경은 {@code catalog_audit} 이 이미 남기므로
 * 여기는 그 밖의 것 — 제보·결손 상태 변경, 수동 작업, 파기 — 이다. 둘을 합쳐 한 타임라인으로 본다.
 * 기록 실패가 작업을 막지 않는다(로그만).
 */
@Component
public class AdminActions {
    private static final Logger log = LoggerFactory.getLogger(AdminActions.class);
    private final JdbcTemplate jdbc;

    public AdminActions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(long actorId, String action, String target, String detail) {
        try {
            jdbc.update("INSERT INTO admin_action(actor_id, action, target, detail) VALUES (?, ?, ?, ?)",
                    actorId, action, target == null ? null : target.substring(0, Math.min(target.length(), 200)),
                    detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000)));
        } catch (RuntimeException e) {
            log.warn("운영 기록 실패 — 작업은 계속한다 ({}): {}", action, e.getClass().getSimpleName());
        }
    }
}
