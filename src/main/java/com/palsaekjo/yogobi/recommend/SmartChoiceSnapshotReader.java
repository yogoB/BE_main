package com.palsaekjo.yogobi.recommend;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 스마트초이스 라이브 시세 스냅샷(V7) 읽기. (통신사, 요금제명)으로 정상가를 찾는다 — 없으면 empty(교차검증 생략). */
@Component
public class SmartChoiceSnapshotReader {
    private final JdbcTemplate jdbc;

    public SmartChoiceSnapshotReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Snapshot(long planPrice, String source, String collectedAt) {
    }

    /** 무약정(contract_months 최소) 정상가를 우선, 같은 조건이면 최신 수집분을 고른다. */
    public Optional<Snapshot> find(String carrier, String planName) {
        return jdbc.query("""
                        SELECT plan_price, source, collected_at FROM smartchoice_plan_snapshot
                        WHERE carrier = ? AND plan_name = ?
                        ORDER BY contract_months ASC, collected_at DESC
                        LIMIT 1
                        """,
                (rs, i) -> new Snapshot(rs.getLong(1), rs.getString(2), rs.getObject(3, java.time.OffsetDateTime.class).toLocalDate().toString()),
                carrier, planName).stream().findFirst();
    }
}
