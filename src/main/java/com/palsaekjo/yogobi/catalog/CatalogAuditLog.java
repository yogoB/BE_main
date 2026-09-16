package com.palsaekjo.yogobi.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 카탈로그 원본 변경 감사 기록(D-26). 원본이 파일이라 git 이력이 없으므로 이 표가 유일한 변경 이력이다.
 *
 * <p>성공(APPLIED)과 되돌린 실패(FAILED)를 모두 남긴다 — 시도 자체가 감사 대상이다.
 * 기록 실패가 변경을 깨뜨리지는 않지만(파일·DB는 이미 반영됨) **조용히 사라지지도 않는다**: ERROR 로 남긴다.
 * 같은 내용을 애플리케이션 로그에도 한 줄 찍어 Fly 로그 수집(D-25)에서 바로 보이게 한다.
 */
@Component
public class CatalogAuditLog {
    private static final Logger log = LoggerFactory.getLogger(CatalogAuditLog.class);
    /** 컬럼 상한(V14)에 맞춰 자른다 — 감사 기록이 길이 때문에 통째로 유실되면 안 된다. */
    private static final int ROW_LIMIT = 4000;
    private static final int DETAIL_LIMIT = 1000;
    private static final int KEY_LIMIT = 500;

    public enum Action { CREATE, UPDATE, DELETE }

    private final JdbcTemplate jdbc;

    public CatalogAuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void applied(long actorId, Action action, String dataset, String key, String before, String after) {
        applied(actorId, action, dataset, key, before, after, null);
    }

    /** {@code detail} 에는 승인 경위(요청 번호·제안자·승인자)를 담는다. D-28. */
    public void applied(long actorId, Action action, String dataset, String key,
                        String before, String after, String detail) {
        record(actorId, action, dataset, key, before, after, "APPLIED", detail);
    }

    public void failed(long actorId, Action action, String dataset, String key, String before, String after,
                       String detail) {
        record(actorId, action, dataset, key, before, after, "FAILED", detail);
    }

    private void record(long actorId, Action action, String dataset, String key,
                        String before, String after, String outcome, String detail) {
        log.info("카탈로그 {} {} {} {} actor={}{}", outcome, action, dataset, key, actorId,
                detail == null ? "" : " (" + detail + ")");
        try {
            jdbc.update("""
                    INSERT INTO catalog_audit (actor_id, action, dataset, row_key, before_row, after_row, outcome, detail)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    actorId, action.name(), dataset, cut(key, KEY_LIMIT),
                    cut(before, ROW_LIMIT), cut(after, ROW_LIMIT), outcome, cut(detail, DETAIL_LIMIT));
        } catch (RuntimeException e) {
            // 변경은 이미 파일·DB에 반영됐다. 되돌리지 않되 기록 실패를 감춰서는 안 된다.
            log.error("카탈로그 감사 기록 실패 — 변경은 반영됨: {} {} {} actor={}", action, dataset, key, actorId, e);
        }
    }

    /** 최근 변경 이력(최신순). 운영자가 "누가 언제 무엇을 바꿨는지"를 실제로 볼 수 있어야 감사 기록이 쓸모 있다. */
    public java.util.List<java.util.Map<String, Object>> recent(int limit) {
        return jdbc.queryForList("""
                SELECT id, actor_id, action, dataset, row_key, before_row, after_row, outcome, detail, created_at
                FROM catalog_audit ORDER BY created_at DESC, id DESC LIMIT ?""", limit);
    }

    private static String cut(String value, int limit) {
        if (value == null) return null;
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
