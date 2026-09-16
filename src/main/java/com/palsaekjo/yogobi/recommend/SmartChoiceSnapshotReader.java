package com.palsaekjo.yogobi.recommend;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 스마트초이스 스냅샷 조회. {@link SmartChoiceSweepService} 가 배치로 모아 둔 행만 읽는다 —
 * 요청 경로에서 외부를 호출하지 않는다(D-05, D-20 이 유지).
 *
 * <p>망·약정은 매칭 키에서 뺐다. 실측에서 30GB 의 LTE·5G 응답이 같은 값으로 왔고
 * API 의 망 구분 정책이 확인되지 않았다(smartchoice-integration-findings.md §9).
 * 잘못 좁혀 "불일치"를 만드는 것보다 (통신사, 요금제명)으로만 찾고 못 찾으면 "확인 못 했다"로 두는 편이 맞다.
 */
@Component
public class SmartChoiceSnapshotReader {

    /** 스냅샷 한 건. 금액에는 출처가 붙는다(절대 원칙 4). */
    public record Snapshot(long planPrice, String source, String sourceUrl, Instant collectedAt) {
    }

    private final JdbcTemplate jdbc;

    public SmartChoiceSnapshotReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 가장 최근에 모은 한 건. 없으면 empty — 그때는 "확인 못 했다"로 응답한다. */
    public Optional<Snapshot> find(String carrier, String planName) {
        if (carrier == null || planName == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT plan_price, source, source_url, collected_at
                  FROM smartchoice_plan_snapshot
                 WHERE lower(btrim(carrier)) = lower(btrim(?)) AND btrim(plan_name) = btrim(?)
                 ORDER BY collected_at DESC
                 LIMIT 1
                """,
                (rs, i) -> new Snapshot(rs.getLong("plan_price"), rs.getString("source"),
                        rs.getString("source_url"), rs.getTimestamp("collected_at").toInstant()),
                carrier, planName).stream().findFirst();
    }
}
