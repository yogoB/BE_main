package com.palsaekjo.yogobi.recommend;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 스마트초이스 스냅샷 조회. {@link SmartChoiceSweepService} 가 배치로 모아 둔 행만 읽는다 —
 * 요청 경로에서 외부를 호출하지 않는다(D-05, D-20 이 유지).
 *
 * <p><b>통신사 표기는 소문자·공백 제거 후 비교한다.</b> 실측 응답의 {@code v_tel} 은 {@code SKT·KT·LGU+}
 * 뿐인데 카탈로그는 {@code LG U+} 로 적는다(evidence/smartchoice-operation-2026-09-16.json).
 * 이 한 줄이 없으면 LG U+ 요금제 88건이 전부 대조에 실패한다.
 *
 * <p>망·약정은 매칭 키에서 뺐다. 30GB 의 LTE·5G 응답이 같은 값으로 왔고 API 의 망 구분 정책이
 * 확인되지 않았다(findings §9). 잘못 좁혀 "불일치"를 만드는 것보다 "확인 못 했다"가 맞다.
 */
@Component
public class SmartChoiceSnapshotReader {

    /** 스냅샷 한 건. 금액에는 출처가 붙는다(절대 원칙 4). */
    public record Snapshot(long planPrice, String source, String sourceUrl, Instant collectedAt) {
    }

    /**
     * 조회 결과. 셋을 구분해야 화면이 정직해진다 —
     * 아직 안 모았다 / 이 통신사는 스마트초이스가 주지 않는다 / 모았는데 이 요금제가 없다.
     */
    public record Lookup(boolean collected, boolean carrierCovered, Optional<Snapshot> found) {
    }

    private final JdbcTemplate jdbc;

    public SmartChoiceSnapshotReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Lookup lookup(String carrier, String planName) {
        if (carrier == null || planName == null) {
            return new Lookup(false, false, Optional.empty());
        }
        // 인덱스를 타는 두 번의 로컬 조회다. 결과 5건이면 10회 — 필요해지면 요청당 1회로 올린다.
        boolean collected = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM smartchoice_plan_snapshot)", Boolean.class));
        if (!collected) {
            return new Lookup(false, false, Optional.empty());
        }
        // 같은 통신사 안에서 요금제명이 정확히 맞는 행을 먼저 본다. 없으면 통신사만 맞는 행이 온다.
        var rows = jdbc.query("""
                SELECT plan_price, source, source_url, collected_at,
                       btrim(plan_name) = btrim(?) AS exact_plan
                  FROM smartchoice_plan_snapshot
                 WHERE replace(lower(btrim(carrier)), ' ', '') = replace(lower(btrim(?)), ' ', '')
                 ORDER BY exact_plan DESC, collected_at DESC
                 LIMIT 1
                """,
                (rs, i) -> new Object[]{rs.getBoolean("exact_plan"),
                        new Snapshot(rs.getLong("plan_price"), rs.getString("source"),
                                rs.getString("source_url"), rs.getTimestamp("collected_at").toInstant())},
                planName, carrier);
        if (rows.isEmpty()) {
            return new Lookup(true, false, Optional.empty());   // 이 통신사는 스마트초이스가 주지 않는다
        }
        boolean exactPlan = (boolean) rows.get(0)[0];
        return new Lookup(true, true, exactPlan ? Optional.of((Snapshot) rows.get(0)[1]) : Optional.empty());
    }
}
