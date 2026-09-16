package com.palsaekjo.yogobi.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 카탈로그에 없어서 요청된 것을 기록한다(D-17 · G-12). {@code requested_cnt} 가 팀의 CSV 수집 우선순위 목록이다.
 * 계산에는 쓰지 않는다 — 여기 쌓인 행은 사람이 CSV에 반영해야 카탈로그가 된다.
 * <p>추천은 비회원 공개 경로라 기록 실패가 요청을 깨뜨리면 안 된다: fail-soft(로그만 남기고 진행).
 */
@Component
public class CatalogCandidateRecorder {
    private static final Logger log = LoggerFactory.getLogger(CatalogCandidateRecorder.class);
    /** 공개 엔드포인트라 임의 입력으로 무한히 늘어날 수 있다. 상한에 닿으면 기록만 멈추고 요청은 그대로 처리한다. */
    private static final int MAX_ROWS = 10_000;

    public enum Kind { MOBILE_PLAN, SUBSCRIPTION_TIER }

    private final JdbcTemplate jdbc;

    public CatalogCandidateRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 같은 결손이 다시 오면 행을 늘리지 않고 요청 횟수만 올린다. */
    public void record(Kind kind, String queryText) {
        if (queryText == null || queryText.isBlank() || queryText.length() > 200) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO catalog_candidate (kind, query_text, status)
                    SELECT ?, ?, 'REQUESTED'
                    WHERE (SELECT count(*) FROM catalog_candidate) < ?
                    ON CONFLICT (kind, query_text) DO UPDATE
                        SET requested_cnt = catalog_candidate.requested_cnt + 1,
                            last_requested_at = now()
                    """, kind.name(), queryText, MAX_ROWS);
        } catch (RuntimeException e) {
            log.warn("카탈로그 결손 기록 실패 — 건너뜀 (fail-soft): {} {} / {}", kind, queryText, e.toString());
        }
    }
}
