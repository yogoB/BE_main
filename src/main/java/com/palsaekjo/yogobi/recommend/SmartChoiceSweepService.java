package com.palsaekjo.yogobi.recommend;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 스마트초이스 공식 시세를 배치로 모아 {@code smartchoice_plan_snapshot} 에 남긴다(D-12).
 *
 * <p>요청마다 부르지 않는다 — 하루 3회 배치이며 사용자 요청 경로는 스냅샷만 읽는다(D-05 유지).
 * 스냅샷은 카탈로그(시드)를 <b>대체하지 않는다</b>. 교차검증·시세 참고용이다(D-12 (3)).
 *
 * <p>격자는 카탈로그에 실제 있는 (망 × 데이터) 조건에서 뽑는다. 전수 격자는 우리가 추천할 수 없는
 * 조건까지 불러 호출만 늘린다. fail-soft: 키 미설정이면 아무것도 하지 않고, 서버에 도달하지 못하면
 * 남은 격자를 건너뛴다(도달성 가드) — 시드 기반 추천은 그대로 동작한다.
 */
@Component
public class SmartChoiceSweepService {
    private static final Logger log = LoggerFactory.getLogger(SmartChoiceSweepService.class);
    private static final int AGE = 20;               // 성인 기준
    private static final int CONTRACT_MONTHS = 0;    // 무약정 정가 — 카탈로그 base_price 와 같은 기준
    private static final String SOURCE_URL = "https://www.smartchoice.or.kr/";
    /** 결손 후보 상한. 닿으면 기록만 멈추고 스윕은 끝까지 간다 — CatalogCandidateRecorder 와 같은 값. */
    private static final int MAX_CANDIDATES = 10_000;

    private final SmartChoiceClient client;
    private final JdbcTemplate jdbc;
    private final int maxConditions;

    public SmartChoiceSweepService(SmartChoiceClient client, JdbcTemplate jdbc,
                                   @Value("${yogobi.smartchoice.max-conditions:60}") int maxConditions) {
        this.client = client;
        this.jdbc = jdbc;
        this.maxConditions = maxConditions;
    }

    /**
     * 스윕 1회. 운영자가 화면에서 결과를 읽고 다음 행동을 정할 수 있게 셋을 구분해 돌려준다 —
     * 키가 없다 / 서버에 닿지 못했다 / 돌았고 몇 행을 남겼다.
     * 닿지 못한 것은 등록 IP·한국망 제한일 수 있어 "실패"와 다르게 읽어야 한다.
     */
    /** 조건 전체를 읽는 안전 상한. 실제 조합은 137개(2026-09-21)다. */
    private static final int ALL_CONDITIONS_CAP = 1_000;

    /**
     * 전체 조건에서 이번 실행이 볼 {@code limit} 개. 시작 위치는 {@code slot × limit} 이고 끝에서 앞으로 감는다 —
     * 시간 단위 slot 이라 하루 세 번의 실행이 서로 다른 창을 본다.
     */
    static <T> List<T> window(List<T> all, int limit, long slot) {
        if (all.size() <= limit) {
            return all;
        }
        int start = (int) ((slot * limit) % all.size());
        var picked = new java.util.ArrayList<T>(limit);
        for (int i = 0; i < limit; i++) {
            picked.add(all.get((start + i) % all.size()));
        }
        return picked;
    }

    public Map<String, Object> sweep() {
        var out = new LinkedHashMap<String, Object>();
        if (!client.enabled()) {
            log.info("스마트초이스 키가 없어 스윕을 건너뜁니다 (시드 추천은 그대로 동작)");
            out.put("enabled", false);
            out.put("conditions", 0);
            out.put("stored", 0);
            out.put("reachable", true);
            out.put("recordedGaps", -1);
            out.put("snapshotRows", snapshotRows());
            return out;
        }
        // 판매 중 요금제의 조건만, 그리고 실행마다 창을 옮긴다(G-80). 앞에서부터 상한만큼 자르면
        // 정렬상 FIVE_G 만 돌고 알뜰폰 대부분인 LTE 는 한 번도 대조되지 않았다. 외부 호출 수는 그대로다.
        List<Map<String, Object>> conditions = window(jdbc.queryForList("""
                SELECT DISTINCT network_type, data_mb
                  FROM mobile_plan
                 WHERE active
                 ORDER BY network_type, data_mb
                 LIMIT ?
                """, ALL_CONDITIONS_CAP), maxConditions, System.currentTimeMillis() / 3_600_000);
        int stored = 0;
        boolean reachable = true;
        for (Map<String, Object> condition : conditions) {
            int dataMb = (int) Math.min(((Number) condition.get("data_mb")).longValue(), SmartChoiceClient.UNLIMITED);
            String networkType = String.valueOf(condition.get("network_type"));
            try {
                for (SmartChoiceRecommendation found : client.recommend(dataMb, SmartChoiceClient.UNLIMITED,
                        SmartChoiceClient.UNLIMITED, AGE, networkCode(networkType), CONTRACT_MONTHS)) {
                    stored += store(found, networkType);
                }
            } catch (SmartChoiceClient.Unreachable e) {
                // 등록 IP·한국망 제한 등으로 서버에 닿지 못했다. 남은 격자도 같을 테니 여기서 멈춘다.
                log.warn("스마트초이스에 도달하지 못해 스윕을 중단합니다 — 이전 스냅샷을 유지합니다");
                reachable = false;
                break;
            }
        }
        log.info("스마트초이스 스윕: 조건 {}건, 스냅샷 {}행 갱신, 도달 {}", conditions.size(), stored, reachable);
        out.put("enabled", true);
        out.put("conditions", conditions.size());
        out.put("stored", stored);
        out.put("reachable", reachable);
        // 예약 작업 기록(ScheduledJobs)은 updated 로 실패를 가른다. 못 닿았는데 "성공"으로 남던 것(G-88 c).
        // 키가 없는 경우는 설정이지 실패가 아니라 싣지 않는다.
        out.put("updated", reachable);
        // 닿지 못한 스윕은 스냅샷을 갱신하지 못했다. 낡은 스냅샷으로 결손을 새로 적지 않는다 —
        // -1 은 "확인 못 함"이며 0("없음")과 구분한다.
        out.put("recordedGaps", reachable ? recordMissingPlans() : -1);
        out.put("snapshotRows", snapshotRows());
        return out;
    }

    /**
     * 스냅샷에 있는데 카탈로그에 없는 요금제를 결손으로 남긴다(G-19 · D-31).
     *
     * <p>스윕은 {@link #AGE} = 20 으로 돌기 때문에 청년 요금제(KT Y덤·SKT 라이트(청년)·LGU+ 유쓰)를
     * 이미 받아오고 있었다. 그 결과가 스냅샷에만 쌓이고 아무도 보지 않아 Y덤 11종이 통째로 빠져 있었다.
     * <p><b>카탈로그로 승격하지 않는다</b> — 스냅샷은 여전히 원본이 아니다(D-20). 여기 쌓인 행은
     * 사람이 CSV 에 반영해야 카탈로그가 된다(D-18). 계산에는 어느 단계에서도 쓰이지 않는다.
     * <p>fail-soft: 기록이 실패해도 이미 저장한 스냅샷은 유지한다. 그래서 실패는 -1(알 수 없음)로 돌려준다.
     */
    private int recordMissingPlans() {
        try {
            // requested_cnt 는 건드리지 않는다 — 그 값은 "사용자가 몇 번 찾았나"이고 곧 수집 우선순위다.
            // 하루 3회 배치가 올리면 아무도 찾지 않은 요금제가 우선순위 1위가 된다(G-19-d).
            int recorded = jdbc.update("""
                    INSERT INTO catalog_candidate (kind, query_text, status)
                    SELECT 'MOBILE_PLAN', btrim(s.carrier) || ' ' || btrim(s.plan_name), 'REQUESTED'
                      FROM smartchoice_plan_snapshot s
                     WHERE length(btrim(s.carrier) || ' ' || btrim(s.plan_name)) <= 200
                       AND NOT EXISTS (
                           SELECT 1 FROM mobile_plan p JOIN carrier c ON c.id = p.carrier_id
                            WHERE replace(lower(btrim(c.name)), ' ', '') = replace(lower(btrim(s.carrier)), ' ', '')
                              AND replace(lower(btrim(p.name)), ' ', '') = replace(lower(btrim(s.plan_name)), ' ', ''))
                       AND (SELECT count(*) FROM catalog_candidate) < ?
                    ON CONFLICT (kind, query_text) DO UPDATE SET last_requested_at = now()
                    """, MAX_CANDIDATES);
            log.info("카탈로그 결손 후보 {}행 기록 (사람이 CSV 에 반영해야 카탈로그가 된다)", recorded);
            return recorded;
        } catch (RuntimeException e) {
            log.warn("결손 후보 기록 실패 — 스냅샷은 유지합니다 (fail-soft): {}", e.toString());
            return -1;
        }
    }

    /** 대조에 실제로 쓸 수 있는 행이 몇 개인지. 0 이면 결과 화면은 전부 "확인 못 했다"로 나온다. */
    private long snapshotRows() {
        Long rows = jdbc.queryForObject("SELECT count(*) FROM smartchoice_plan_snapshot", Long.class);
        return rows == null ? 0 : rows;
    }

    /** 이름·통신사가 비면 대조 키가 없으므로 버린다. 같은 키는 최신 값으로 덮는다(V7 의 dedup 키). */
    private int store(SmartChoiceRecommendation found, String networkType) {
        String carrier = found.carrier() == null ? "" : found.carrier().strip();
        String planName = found.planName() == null ? "" : found.planName().strip();
        if (carrier.isEmpty() || planName.isEmpty() || found.planPrice() < 0) {
            return 0;
        }
        return jdbc.update("""
                INSERT INTO smartchoice_plan_snapshot
                    (carrier, plan_name, network_type, contract_months, plan_price, discounted_price,
                     display_data, source_url, collected_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (carrier, plan_name, network_type, contract_months) DO UPDATE SET
                    plan_price = EXCLUDED.plan_price, discounted_price = EXCLUDED.discounted_price,
                    display_data = EXCLUDED.display_data, collected_at = EXCLUDED.collected_at
                """, carrier, planName, display(networkType), CONTRACT_MONTHS,
                found.planPrice(), Math.max(found.discountedPrice(), 0), found.displayData(), SOURCE_URL);
    }

    /** 카탈로그 enum 을 스냅샷 표기로. V7 의 network_type 은 3G|LTE|5G 다. */
    private static String display(String networkType) {
        return switch (networkType == null ? "" : networkType) {
            case "FIVE_G" -> "5G";
            case "THREE_G" -> "3G";
            default -> "LTE";
        };
    }

    /** data.md §2: 3G=2 · LTE=3 · 5G=6. */
    private static int networkCode(String networkType) {
        return switch (networkType == null ? "" : networkType) {
            case "FIVE_G" -> 6;
            case "THREE_G" -> 2;
            default -> 3;
        };
    }
}
